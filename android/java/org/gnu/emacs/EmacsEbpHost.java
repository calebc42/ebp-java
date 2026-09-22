/* The jetpacs-native fork's EBP Companion host.  -*- c-file-style: "GNU" -*-

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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.engine.Bundle;
import com.calebc42.ebp.companion.engine.Clock;
import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionConfig;
import com.calebc42.ebp.companion.engine.CompanionEngine;
import com.calebc42.ebp.companion.engine.Failpoints;
import com.calebc42.ebp.companion.engine.M1Profile;
import com.calebc42.ebp.companion.engine.Presentation;
import com.calebc42.ebp.companion.host.LoopbackServer;
import com.calebc42.ebp.companion.host.PairingFile;
import com.calebc42.ebp.companion.android.AndroidRasterizer;
import com.calebc42.ebp.companion.android.AndroidSqliteStore;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.SessionState;

import java.io.File;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/* EXPERIMENTAL; NOT FOR UPSTREAM SUBMISSION.

   The Java EBP Companion endpoint hosted inside the Emacs process.  It
   owns the loopback listener, the SQLite-backed durable store, and a
   cache of immutable presentation bundles, one per surface and
   generation.  Lisp reaches it only through the static methods below,
   called from the Emacs thread by the DEFUNs in androidfns.c; none of
   them blocks on the endpoint executor, a socket, or SQLite.  The
   endpoint tells Lisp that something changed through the existing
   jetpacs event route with a small ASCII payload, posted from the main
   thread like every other producer of that queue.  */

public final class EmacsEbpHost
{
  private static final String TAG = "EmacsEbpHost";

  /* Bound on activations accepted but not yet processed by the
     endpoint executor.  */
  private static final int MAX_PENDING_ACTIVATIONS = 64;

  private static Companion companion;
  private static LoopbackServer server;
  private static ExecutorService executor;
  private static AndroidSqliteStore store;
  private static PairingFile pairing;
  private static int port = -1;
  private static Handler mainHandler;
  private static final AtomicInteger pendingActivations
    = new AtomicInteger (0);

  /* surface -> the latest published bundle; each array is immutable
     once stored.  */
  private static final Map<String, byte[]> published
    = new HashMap<String, byte[]> ();

  private EmacsEbpHost () {}

  /* Post PAYLOAD (ASCII) to Lisp through the jetpacs event route,
     from the main thread.  */

  private static void
  notifyLisp (final String payload)
  {
    Handler handler = mainHandler;

    if (handler == null)
      return;

    handler.post (new Runnable () {
	@Override
	public void
	run ()
	{
	  if (EmacsService.SERVICE != null)
	    EmacsNative.sendJetpacsEvent (payload);
	}
      });
  }

  /* Rebuild and publish SURFACE's bundle.  Runs on the endpoint
     executor.  */

  private static void
  publish (String surface)
  {
    Companion c = companion;

    if (c == null)
      return;

    Presentation p = c.presentation (pairing.pairingId, surface);
    byte[] bundle = Bundle.build (p, c.generation (),
				  new AndroidRasterizer ());

    synchronized (published)
      {
	published.put (surface, bundle);
      }

    notifyLisp ("ebp:surface:" + surface);
  }

  public static synchronized int
  start (int requestedPort)
  {
    if (companion != null)
      return port;

    Context context = EmacsService.SERVICE;

    if (context == null)
      return -1;

    try
      {
	mainHandler = new Handler (Looper.getMainLooper ());
	File dir = new File (context.getFilesDir (), "ebp-companion");
	pairing = PairingFile.loadOrCreate (new File (dir, "pairing.json"),
					    Auth.SECURE_RANDOM);
	store = new AndroidSqliteStore (new File (dir, "companion.sqlite"));
	executor = LoopbackServer.newOrderedExecutor ();

	Map<String, byte[]> pairings = new HashMap<String, byte[]> ();
	pairings.put (pairing.pairingId, pairing.token ());
	CompanionConfig config
	  = new CompanionConfig ("emacs-android-ebp", "0.1.0", pairings,
				 new HashSet<String> (),
				 M1Profile.surfaceProfiles (),
				 M1Profile.limits (),
				 Auth.SECURE_RANDOM);

	Companion.Listener listener = new Companion.Listener () {
	    @Override
	    public void
	    surfaceChanged (final String surface)
	    {
	      /* Rebuild after the current dispatch finishes, off the
		 Companion monitor.  */
	      executor.execute (new Runnable () {
		  @Override
		  public void
		  run ()
		  {
		    publish (surface);
		  }
		});
	    }

	    @Override
	    public void
	    sessionChanged (SessionState state)
	    {
	      notifyLisp ("ebp:session:" + state.name ());
	    }

	    @Override
	    public void
	    queueChanged ()
	    {
	      notifyLisp ("ebp:queue");
	    }

	    @Override
	    public void
	    settingsRequested ()
	    {
	      notifyLisp ("ebp:settings");
	    }

	    @Override
	    public void
	    diagnostic (String message)
	    {
	      Log.w (TAG, "diagnostic: " + message);
	      notifyLisp ("ebp:diag");
	    }
	  };

	companion = new Companion (config, store, Clock.SYSTEM, executor,
				   listener);

	LoopbackServer.TripHandler trip = new LoopbackServer.TripHandler () {
	    @Override
	    public void
	    tripped (Failpoints.Tripped t)
	    {
	      /* Deterministic process death at the named boundary.  */
	      Log.w (TAG, "failpoint tripped: " + t.name);
	      Runtime.getRuntime ().halt (3);
	    }
	  };

	server = new LoopbackServer (companion, executor, trip);
	port = server.start (requestedPort);

	/* Republish every retained surface so a relaunch shows the
	   cached screen before any session exists.  */
	final Json.Obj surfaces = companion.welcomeSurfaces (pairing.pairingId);
	executor.execute (new Runnable () {
	    @Override
	    public void
	    run ()
	    {
	      for (String surface : surfaces.keys ())
		publish (surface);
	    }
	  });

	Log.i (TAG, "EBP companion listening on 127.0.0.1:" + port);
	return port;
      }
    catch (Exception e)
      {
	Log.e (TAG, "start failed", e);
	stop ();
	return -1;
      }
  }

  public static synchronized void
  stop ()
  {
    if (server != null)
      server.stop ();

    if (executor != null)
      executor.shutdown ();

    if (store != null)
      store.close ();

    synchronized (published)
      {
	published.clear ();
      }

    server = null;
    executor = null;
    store = null;
    companion = null;
    port = -1;
  }

  public static synchronized String
  credentials ()
  {
    if (companion == null || pairing == null)
      return null;

    return pairing.pairingId + ":" + pairing.tokenDisplay;
  }

  public static byte[]
  status ()
  {
    Companion c = companion;
    PairingFile p = pairing;

    if (c == null || p == null)
      return null;

    CompanionEngine engine = c.currentEngine ();
    Json.Obj o = new Json.Obj ()
      .put ("running", true)
      .put ("port", port)
      .put ("session", engine == null ? "none" : engine.state ().name ())
      .put ("pairing_id", p.pairingId)
      .put ("queued_events", c.queuedCount (p.pairingId))
      .put ("in_flight", c.inFlight (p.pairingId))
      .put ("pump_paused", engine != null && engine.pumpPaused ())
      .put ("generation", c.generation ())
      .put ("surfaces", c.welcomeSurfaces (p.pairingId));
    String err = c.lastError ();

    if (err != null)
      o.put ("last_error", err);

    return Json.writeUtf8 (o);
  }

  public static byte[]
  snapshot (String surface)
  {
    if (surface == null)
      return null;

    synchronized (published)
      {
	return published.get (surface);
      }
  }

  public static int
  activate (final String surface, final long revision, final String path)
  {
    final Companion c = companion;
    final ExecutorService ex = executor;
    final PairingFile p = pairing;

    if (c == null || ex == null || p == null)
      return 2;

    if (surface == null || path == null || revision < 0)
      return 3;

    if (pendingActivations.incrementAndGet () > MAX_PENDING_ACTIVATIONS)
      {
	pendingActivations.decrementAndGet ();
	return 1;
      }

    try
      {
	ex.execute (new Runnable () {
	    @Override
	    public void
	    run ()
	    {
	      try
		{
		  Companion.Admission a = c.activate (p.pairingId, surface,
						      revision, path);
		  Log.i (TAG, "activation " + path + "@" + revision + ": " + a);
		}
	      catch (Failpoints.Tripped t)
		{
		  Log.w (TAG, "failpoint tripped: " + t.name);
		  Runtime.getRuntime ().halt (3);
		}
	      finally
		{
		  pendingActivations.decrementAndGet ();
		  notifyLisp ("ebp:queue");
		}
	    }
	  });
      }
    catch (RuntimeException e)
      {
	pendingActivations.decrementAndGet ();
	return 2;
      }

    return 0;
  }

  public static boolean
  armFailpoint (String name)
  {
    Context context = EmacsService.SERVICE;

    if (context == null || name == null)
      return false;

    /* Test-only: never available in a release build.  */
    if ((context.getApplicationInfo ().flags
	 & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
      return false;

    return Failpoints.arm (name);
  }

  /* Keep the charset reference explicit for readers of the bundle
     contract: everything textual crosses as UTF-8 octets.  */
  static final Charset UTF_8 = Charset.forName ("UTF-8");
};
