/* The jetpacs-native fork's EBP wake service.  -*- c-file-style: "GNU" -*-

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
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/* EXPERIMENTAL; NOT FOR UPSTREAM SUBMISSION.

   The SPEC 5.8 same-package bound-service wake target, in the Emacs
   process.  The agent binds it with no extras; the bind starts this
   process and, here, the Emacs service.  The binder exposes nothing and
   the intent's extras are never read: the bind is the whole signal, and
   there is no parameter by which it could influence how Emacs
   initializes.  The platform may refuse the foreground-service start
   from this position (a background bind on Android 12 and later); that
   is logged and is not an error of the protocol.  */

public final class EmacsWakeService extends Service
{
  private static final String TAG = "EmacsWakeService";

  @Override
  public void
  onCreate ()
  {
    super.onCreate ();

    try
      {
	EmacsService.startEmacsService (this);
      }
    catch (Exception e)
      {
	/* ForegroundServiceStartNotAllowedException and kin: the wake
	   attempt failed; the queued event is retained (SPEC 5.8).  */
	Log.w (TAG, "could not start the Emacs service", e);
      }
  }

  @Override
  public IBinder
  onBind (Intent intent)
  {
    /* An operation-less binder: nothing to call.  */
    return new Binder ();
  }
}
