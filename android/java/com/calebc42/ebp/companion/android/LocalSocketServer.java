// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 5.3 `loopback-unix` listener for Android: a filesystem-namespace
// Unix-domain socket in the application's private files directory (never
// the abstract namespace, which SPEC 5.3 forbids), the socket file 0600, a
// stale socket of ours unlinked before the bind and a connectable one
// refused.  On accept the peer's credentials are checked against this
// process's UID — stronger than the filesystem floor SPEC 5.3 settles for,
// and what makes the socket safe to share between the Emacs process and
// the :agent process of one package.  Each accepted socket enters the SPEC
// 5.7 pool and is served by StreamSession.  Compiled only inside the Emacs
// tree (android.* is unavailable to the core).
package com.calebc42.ebp.companion.android;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionEngine;
import com.calebc42.ebp.companion.host.LoopbackServer;
import com.calebc42.ebp.companion.host.PreauthPool;
import com.calebc42.ebp.companion.host.StreamSession;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;

public final class LocalSocketServer
{
  private static final String TAG = "EbpLocalSocketServer";

  private final Companion companion;
  private final ExecutorService executor;
  private final LoopbackServer.TripHandler tripHandler;
  private final PreauthPool pool = new PreauthPool ();
  private LocalServerSocket server;
  private LocalSocket bound;
  private File path;
  private Thread acceptThread;
  private volatile boolean running;

  public
  LocalSocketServer (Companion companion, ExecutorService executor,
		     LoopbackServer.TripHandler tripHandler)
  {
    this.companion = companion;
    this.executor = executor;
    this.tripHandler = tripHandler;
  }

  /* Non-nil when something accepts connections at PATH right now.  */

  private static boolean
  connectable (File path)
  {
    LocalSocket probe = new LocalSocket ();

    try
      {
	probe.connect (new LocalSocketAddress (path.getPath (),
					       LocalSocketAddress.Namespace.FILESYSTEM));
	return true;
      }
    catch (IOException e)
      {
	return false;
      }
    finally
      {
	try
	  {
	    probe.close ();
	  }
	catch (IOException ignored)
	  {
	    /* closing */
	  }
      }
  }

  /* Bind PATH and start accepting.  The directory is created 0700.  */

  public synchronized void
  start (File socketPath) throws IOException
  {
    if (running)
      return;

    File dir = socketPath.getAbsoluteFile ().getParentFile ();

    if (!dir.isDirectory () && !dir.mkdirs ())
      throw new IOException ("cannot create " + dir);

    try
      {
	Os.chmod (dir.getPath (), 0700);
      }
    catch (ErrnoException e)
      {
	throw new IOException ("chmod " + dir + ": " + e.getMessage ());
      }

    if (socketPath.exists ())
      {
	if (connectable (socketPath))
	  throw new IOException ("socket in use: " + socketPath);

	if (!socketPath.delete ())
	  throw new IOException ("cannot unlink stale socket " + socketPath);
      }

    bound = new LocalSocket ();
    bound.bind (new LocalSocketAddress (socketPath.getPath (),
					LocalSocketAddress.Namespace.FILESYSTEM));
    server = new LocalServerSocket (bound.getFileDescriptor ());

    try
      {
	Os.chmod (socketPath.getPath (), 0600);
      }
    catch (ErrnoException e)
      {
	throw new IOException ("chmod " + socketPath + ": " + e.getMessage ());
      }

    path = socketPath;
    running = true;
    acceptThread = new Thread (new Runnable () {
	@Override
	public void
	run ()
	{
	  acceptLoop ();
	}
      }, "ebp-accept-local");
    acceptThread.setDaemon (true);
    acceptThread.start ();
  }

  public synchronized File
  path ()
  {
    return path;
  }

  public synchronized void
  stop ()
  {
    running = false;

    if (server != null)
      {
	try
	  {
	    server.close ();
	  }
	catch (IOException ignored)
	  {
	    /* closing */
	  }
      }

    if (bound != null)
      {
	try
	  {
	    bound.close ();
	  }
	catch (IOException ignored)
	  {
	    /* closing */
	  }
      }

    pool.shutdown ();

    if (path != null)
      path.delete ();

    final CompanionEngine current = companion.currentEngine ();

    if (current != null)
      executor.execute (new Runnable () {
	  @Override
	  public void
	  run ()
	  {
	    current.close ("host shutdown");
	  }
	});
  }

  private void
  acceptLoop ()
  {
    while (running)
      {
	final LocalSocket socket;

	try
	  {
	    socket = server.accept ();
	  }
	catch (IOException e)
	  {
	    if (running)
	      continue;
	    return;
	  }

	/* SPEC 5.3 floor plus the process-credential check: only this
	   application's UID may speak to this socket.  */
	try
	  {
	    Credentials peer = socket.getPeerCredentials ();

	    if (peer.getUid () != android.os.Process.myUid ())
	      {
		Log.w (TAG, "refusing peer uid " + peer.getUid ());
		socket.close ();
		continue;
	      }
	  }
	catch (IOException e)
	  {
	    try
	      {
		socket.close ();
	      }
	    catch (IOException ignored)
	      {
		/* closing */
	      }
	    continue;
	  }

	pool.admit (socket);
	Thread reader = new Thread (new Runnable () {
	    @Override
	    public void
	    run ()
	    {
	      serve (socket);
	    }
	  }, "ebp-reader-local");
	reader.setDaemon (true);
	reader.start ();
      }
  }

  private void
  serve (final LocalSocket socket)
  {
    final InputStream in;
    final OutputStream out;

    try
      {
	in = socket.getInputStream ();
	out = socket.getOutputStream ();
      }
    catch (IOException e)
      {
	pool.remove (socket);

	try
	  {
	    socket.close ();
	  }
	catch (IOException ignored)
	  {
	    /* closing */
	  }
	return;
      }

    StreamSession.serve (companion, executor, tripHandler, pool, in, out, socket);
  }

  /* Referenced so the shrinker keeps the constant class this file's
     documentation cites; OsConstants is otherwise unused here.  */
  static int
  touch ()
  {
    return OsConstants.AF_UNIX;
  }
}
