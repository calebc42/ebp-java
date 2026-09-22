/* The jetpacs-native fork's EBP agent service.  -*- c-file-style: "GNU" -*-

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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/* EXPERIMENTAL; NOT FOR UPSTREAM SUBMISSION.

   The started service that hosts EbpAgentHost in the `:agent' process
   (see the manifest).  It is not exported and returns no binder; Emacs
   reaches the Companion over the SPEC 5.3 socket only.  START_STICKY:
   the agent is the durable end of the pairing and the system should
   resurrect it.  Like JetpacsAgentReceiver, this class must never
   reference EmacsNative or EmacsService.  */

public final class EmacsEbpAgentService extends Service
{
  private static final String TAG = "EmacsEbpAgentService";

  /* Ask the system to start the agent, from any process of this
     package.  */

  public static void
  start (Context context)
  {
    context.startService (new Intent (context, EmacsEbpAgentService.class));
  }

  @Override
  public int
  onStartCommand (Intent intent, int flags, int startId)
  {
    if (!EbpAgentHost.start (this))
      Log.e (TAG, "agent host failed to start");

    return START_STICKY;
  }

  @Override
  public IBinder
  onBind (Intent intent)
  {
    return null;
  }

  @Override
  public void
  onDestroy ()
  {
    EbpAgentHost.stop ();
    super.onDestroy ();
  }
}
