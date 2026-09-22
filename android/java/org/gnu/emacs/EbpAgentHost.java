/* The jetpacs-native fork's EBP Companion agent host.  -*- c-file-style: "GNU" -*-

Copyright (C) 2026 Free Software Foundation, Inc.

This file is part of GNU Emacs.

GNU Emacs is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or (at
your option) any later version.

GNU Emacs is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GNU Emacs.  If not, see <https://www.gnu.org/licenses/>.  */

package org.gnu.emacs;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.engine.Clock;
import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionConfig;
import com.calebc42.ebp.companion.engine.Failpoints;
import com.calebc42.ebp.companion.engine.M1Profile;
import com.calebc42.ebp.companion.host.LoopbackServer;
import com.calebc42.ebp.companion.host.PairingFile;
import com.calebc42.ebp.companion.android.AndroidSqliteStore;
import com.calebc42.ebp.companion.android.LocalSocketServer;
import com.calebc42.ebp.companion.rpc.SessionState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/* EXPERIMENTAL; NOT FOR UPSTREAM SUBMISSION.

   The EBP Companion endpoint hosted in the `:agent' process of this
   package: a SPEC 5.3 `loopback-unix' listener in the application's
   private files directory, a SQLite-backed durable store, and its own
   pairing identity, all of which outlive the Emacs process.  Emacs is a
   wire peer here and nothing else: this class MUST NOT reference
   EmacsNative, EmacsService, or any class that loads the Emacs runtime,
   because the agent process exists to be alive when Emacs is not.

   The SPEC 5.8 same-package bound-service wake: after a `wake'
   occurrence is durably admitted with no READY session, bind (with no
   extras) the non-exported EmacsWakeService in the Emacs process, which
   starts the host; release the binding at READY or after ten seconds.
   The user configures the target by creating the file `wake-target'
   with the content `bound-service' beside the socket; `offline.wake' is
   granted only while that file exists, and it is re-checked immediately
   before each bind.  */

public final class EbpAgentHost
{
  private static final String TAG = "EbpAgentHost";

  /* SPEC 5.8: at most one signal per pairing per 60 seconds, and a
     signal waits at most 10 seconds.  */
  private static final long WAKE_INTERVAL_MS = 60000L;
  private static final long WAKE_WAIT_MS = 10000L;
  private static final String WAKE_TARGET = "bound-service";

  private static Companion companion;
  private static LocalSocketServer server;
  private static ExecutorService executor;
  private static AndroidSqliteStore store;
  private static PairingFile pairing;
  private static Context context;
  private static Handler mainHandler;
  private static File socketFile;

  /* Wake state, guarded by the class monitor.  */
  private static long lastWakeMs;
  private static ServiceConnection wakeConnection;
  private static Runnable wakeRelease;

  private EbpAgentHost () {}

  /* The agent's private directory under CONTEXT's files directory.  */

  public static File
  directory (Context context)
  {
    return new File (context.getFilesDir (), "ebp-agent");
  }

  /* The socket path Emacs dials (SPEC 5.3).  */

  public static File
  socketPath (Context context)
  {
    return new File (directory (context), "agent.sock");
  }

  /* Whether the user has configured the SPEC 5.8 bound-service wake
     target.  Re-read on every call, as the section requires.  */

  private static boolean
  wakeConfigured (File dir)
  {
    File target = new File (dir, "wake-target");

    if (!target.isFile ())
      return false;

    try
      {
	byte[] buf = new byte[64];
	java.io.FileInputStream in = new java.io.FileInputStream (target);
	int n;

	try
	  {
	    n = in.read (buf);
	  }
	finally
	  {
	    in.close ();
	  }

	return n > 0 && WAKE_TARGET.equals (new String (buf, 0, n, "UTF-8").trim ());
      }
    catch (IOException e)
      {
	return false;
      }
  }

  /* Write the pairing credentials for Lisp, mode 0600, in the same
     `PAIRING-ID:TOKEN' form `android-ebp-credentials' returns.  */

  private static void
  writeCredentials (File dir) throws IOException
  {
    File file = new File (dir, "agent-credentials");
    OutputStream out = new FileOutputStream (file);

    try
      {
	out.write ((pairing.pairingId + ":" + pairing.tokenDisplay + "\n")
		   .getBytes ("UTF-8"));
      }
    finally
      {
	out.close ();
      }

    try
      {
	Os.chmod (file.getPath (), 0600);
      }
    catch (ErrnoException e)
      {
	throw new IOException ("chmod " + file + ": " + e.getMessage ());
      }
  }

  public static synchronized boolean
  start (Context ctx)
  {
    if (companion != null)
      return true;

    context = ctx.getApplicationContext ();

    try
      {
	mainHandler = new Handler (Looper.getMainLooper ());
	File dir = directory (context);

	if (!dir.isDirectory () && !dir.mkdirs ())
	  throw new IOException ("cannot create " + dir);

	Os.chmod (dir.getPath (), 0700);
	pairing = PairingFile.loadOrCreate (new File (dir, "pairing.json"),
					    Auth.SECURE_RANDOM);
	writeCredentials (dir);
	store = new AndroidSqliteStore (new File (dir, "agent.sqlite"));
	executor = LoopbackServer.newOrderedExecutor ();

	Map<String, byte[]> pairings = new HashMap<String, byte[]> ();
	pairings.put (pairing.pairingId, pairing.token ());
	Set<String> capabilities = new HashSet<String> ();

	/* SPEC 5.8: grant offline.wake only when the platform mechanism
	   is defined and the user has configured its target.  */
	if (wakeConfigured (dir))
	  capabilities.add ("offline.wake");

	CompanionConfig config
	  = new CompanionConfig ("emacs-android-agent", "0.1.0", pairings,
				 capabilities, M1Profile.surfaceProfiles (),
				 M1Profile.limits (), Auth.SECURE_RANDOM);

	Companion.Listener listener = new Companion.Listener () {
	    @Override
	    public void
	    surfaceChanged (String surface)
	    {
	      /* Wire endpoint only: nothing is presented here.  */
	    }

	    @Override
	    public void
	    sessionChanged (SessionState state)
	    {
	      if (state == SessionState.READY)
		releaseWake ();
	    }

	    @Override
	    public void
	    queueChanged ()
	    {
	    }

	    @Override
	    public void
	    settingsRequested ()
	    {
	    }

	    @Override
	    public void
	    diagnostic (String message)
	    {
	      Log.w (TAG, "diagnostic: " + message);
	    }
	  };

	companion = new Companion (config, store, Clock.SYSTEM, executor,
				   listener);
	companion.setWakeHandler (new Companion.WakeHandler () {
	    @Override
	    public void
	    wake (String pairingId)
	    {
	      requestWake ();
	    }
	  });

	LoopbackServer.TripHandler trip = new LoopbackServer.TripHandler () {
	    @Override
	    public void
	    tripped (Failpoints.Tripped t)
	    {
	      Log.w (TAG, "failpoint tripped: " + t.name);
	      Runtime.getRuntime ().halt (3);
	    }
	  };

	server = new LocalSocketServer (companion, executor, trip);
	socketFile = socketPath (context);
	server.start (socketFile);
	Log.i (TAG, "EBP agent listening on " + socketFile);
	return true;
      }
    catch (Exception e)
      {
	Log.e (TAG, "start failed", e);
	stop ();
	return false;
      }
  }

  public static synchronized void
  stop ()
  {
    releaseWake ();

    if (server != null)
      server.stop ();

    if (executor != null)
      executor.shutdown ();

    if (store != null)
      store.close ();

    companion = null;
    server = null;
    executor = null;
    store = null;
    mainHandler = null;
    socketFile = null;
  }

  /* SPEC 5.8: the bound-service signal.  Runs on the ordered executor
     after a durable `wake' admission; the bind itself is posted to the
     main thread, where ServiceConnection callbacks arrive.  */

  private static synchronized void
  requestWake ()
  {
    final Context ctx = context;
    Handler handler = mainHandler;
    long now = System.currentTimeMillis ();

    if (ctx == null || handler == null)
      return;

    /* Coalesce: at most one signal per 60 seconds, and never while a
       binding is still held.  */
    if (wakeConnection != null || now - lastWakeMs < WAKE_INTERVAL_MS)
      return;

    /* Re-check the exact user-approved target immediately before each
       signal (SPEC 5.8).  */
    if (!wakeConfigured (directory (ctx)))
      {
	Log.w (TAG, "wake target no longer configured; event retained as queue");
	return;
      }

    lastWakeMs = now;
    final ServiceConnection connection = new ServiceConnection () {
	@Override
	public void
	onServiceConnected (ComponentName name, IBinder binder)
	{
	  Log.d (TAG, "wake service bound");
	}

	@Override
	public void
	onServiceDisconnected (ComponentName name)
	{
	  Log.d (TAG, "wake service disconnected");
	}
      };
    wakeConnection = connection;
    wakeRelease = new Runnable () {
	@Override
	public void
	run ()
	{
	  releaseWake ();
	}
      };

    handler.post (new Runnable () {
	@Override
	public void
	run ()
	{
	  /* No extras: the bind is the whole signal.  */
	  Intent intent = new Intent (ctx, EmacsWakeService.class);

	  try
	    {
	      if (!ctx.bindService (intent, connection, Context.BIND_AUTO_CREATE))
		{
		  Log.w (TAG, "bindService refused");
		  releaseWake ();
		}
	    }
	  catch (Exception e)
	    {
	      Log.w (TAG, "bindService failed", e);
	      releaseWake ();
	    }
	}
      });
    handler.postDelayed (wakeRelease, WAKE_WAIT_MS);
  }

  /* Release the wake binding, if any: at READY, at the 10-second
     bound, or when the host stops.  */

  private static synchronized void
  releaseWake ()
  {
    final ServiceConnection connection = wakeConnection;
    final Context ctx = context;
    Handler handler = mainHandler;

    if (wakeRelease != null && handler != null)
      handler.removeCallbacks (wakeRelease);

    wakeRelease = null;
    wakeConnection = null;

    if (connection == null || ctx == null || handler == null)
      return;

    handler.post (new Runnable () {
	@Override
	public void
	run ()
	{
	  try
	    {
	      ctx.unbindService (connection);
	    }
	  catch (IllegalArgumentException e)
	    {
	      /* Never bound; nothing to release.  */
	    }
	}
      });
  }
}
