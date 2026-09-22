;;; ebpm1-app.el --- EBP milestone application: one durable flag -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The M1 milestone application of ebp-java-canvas-milestone-plan.md.
;; It owns exactly one durable Boolean flag, publishes a screen derived
;; from that flag as the EBP surface `app:milestone', and accepts the
;; semantic action `milestone.flag-set' delivered by the Java Companion.
;;
;; The file is platform neutral: the same code drives ebp-java's host
;; runner and the Android renderer in `ebpm1-android.el'.  It never
;; reads Companion state and renders nothing itself; ebp.el owns the
;; wire, the surface revisions, and the EventId receipts.  The flag
;; lives in an application-owned SQLite file, separate from ebp.el's
;; receipt store and from the Companion's own store.
;;
;; Every member the screen authors is one the Companion advertises in
;; its `members' profile (`ebpm1-advertised-members').  ebp.el does not
;; enforce that profile itself, so `test/ebpm1-app-test.el' checks the
;; authored document against it.

;;; Code:

(require 'cl-lib)
(require 'sqlite)
(require 'ebp)

(defgroup ebpm1 nil
  "EBP M1 milestone application."
  :group 'applications)

(defconst ebpm1-surface "app:milestone"
  "The one surface this application publishes (SPEC 13.1).")

(defconst ebpm1-action "milestone.flag-set"
  "The one semantic action this application allowlists (SPEC 14.1).")

(defconst ebpm1-ttl-s 300
  "Queue lifetime in seconds authored on both buttons (SPEC 14.1).")

(defconst ebpm1-advertised-node-types '("column" "text" "button" "canvas")
  "The node types the milestone Companion advertises for `app'.")

(defconst ebpm1-advertised-builtins '("view.switch" "companion.settings.open")
  "The builtins the milestone Companion advertises for `app'.")

(defconst ebpm1-advertised-members
  '((universal . ("id" "key"))
    (nodes . (("text" . ("text" "style"))
              ("column" . ("children" "spacing"))
              ("button" . ("label" "on_tap" "enabled"))
              ("canvas" . ("width" "height" "ops"))))
    (semantics . ())
    (surface . ("views" "initial_view")))
  "The SPEC 10.2 `members' profile the milestone Companion advertises.
Every member the screen authors must appear in the matching list.")

(defcustom ebpm1-flag-file "ebpm1-flag.sqlite"
  "SQLite file holding the milestone flag.
A relative name resolves against `ebp-directory'; an absolute name is
used as given.  This is application storage, separate from ebp.el's
receipt store and from the Companion's store."
  :type 'file)

(defvar ebpm1-client nil
  "The `ebp-connect' client started by `ebpm1-app-start', or nil.")

(defvar ebpm1-after-accept-functions nil
  "Abnormal hook run with the committed value after an accepted action.
The argument is t when the flag was set on and nil when set off.")

(defvar ebpm1-state-functions nil
  "Abnormal hook run with (CLIENT STATE) on session milestones.
STATE is `connecting' from `ebpm1-app-start', `ready' when the
session reaches READY, and `closed' from `ebpm1-app-stop'.")

(defconst ebpm1-storage-failure-status 'ebpm1-storage-failed
  "The handler's answer when the flag could not be written.
It is deliberately none of ebp.el's `accepted', `stale', and `rejected'
statuses: the dispatcher answers an unknown symbol with -32603
internal-error, a JSON-RPC error, and SPEC 15.3 makes the Companion
retain the event and pause its pump on any such error.  Signalling from
the handler would instead be answered `rejected', which SPEC 14.4 makes
permanent, and a transient storage fault is not permanent.  The pump
resumes at the next `queue.replay'.")

;;;; The durable flag

(defvar ebpm1--db nil
  "Open SQLite handle for `ebpm1-flag-file', or nil.")

(defun ebpm1-flag-path ()
  "Return the absolute path of the flag database."
  (expand-file-name ebpm1-flag-file ebp-directory))

(defun ebpm1--db ()
  "Return the open flag database, opening and initializing it on demand."
  (unless ebpm1--db
    (let* ((path (ebpm1-flag-path))
           (directory (file-name-directory path)))
      (when directory
        (make-directory directory t))
      (let ((db (sqlite-open path)))
        (sqlite-pragma db "journal_mode = WAL")
        (sqlite-pragma db "synchronous = FULL")
        (sqlite-execute
         db
         "CREATE TABLE IF NOT EXISTS flag (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            value INTEGER NOT NULL CHECK (value IN (0, 1)))")
        (setq ebpm1--db db))))
  ebpm1--db)

(defun ebpm1-flag-close ()
  "Close the flag database; the next access reopens it."
  (when ebpm1--db
    (ignore-errors (sqlite-close ebpm1--db))
    (setq ebpm1--db nil)))

(defun ebpm1-flag ()
  "Return the durable flag: t when on, nil when off or never set."
  (let ((row (car (sqlite-select (ebpm1--db)
                                 "SELECT value FROM flag WHERE id = 1"))))
    (and row (eql (car row) 1))))

(defun ebpm1-flag-set (desired)
  "Durably set the flag to DESIRED (nil or non-nil); return the stored value.
The write commits inside an explicit BEGIN IMMEDIATE transaction whose
COMMIT result is checked, so a false result never passes as a commit.
Setting a value again is a no-op by construction: replaying the action
is safe, which an increment would not be."
  (let ((db (ebpm1--db))
        (value (if desired 1 0))
        begun committed)
    (unwind-protect
        (progn
          (unless (integerp (sqlite-execute db "BEGIN IMMEDIATE"))
            (error "ebpm1: BEGIN IMMEDIATE did not succeed"))
          (setq begun t)
          (sqlite-execute
           db
           "INSERT INTO flag (id, value) VALUES (1, ?)
              ON CONFLICT (id) DO UPDATE SET value = excluded.value"
           (list value))
          (unless (sqlite-commit db)
            (error "ebpm1: COMMIT did not succeed"))
          (setq committed t))
      (when (and begun (not committed))
        (ignore-errors (sqlite-rollback db))))
    (= value 1)))

;;;; The screen

(defun ebpm1--descriptor (desired)
  "The remote ActionDescriptor setting the flag to DESIRED (SPEC 14.1)."
  `(:action ,ebpm1-action
    :args (:desired ,(if desired t :json-false))
    :when_offline "queue"
    :ttl_s ,ebpm1-ttl-s))

(defun ebpm1-spec (on)
  "Return the SurfaceSpec plist for the flag state ON (t or nil).
The document uses only advertised node types and members: a column
holding a title, the authoritative flag text, an opaque Canvas
indicator whose color the flag selects, and the two buttons.  Both
buttons stay enabled because a repeated set is harmless."
  (let ((fill (if on "#2e7d32" "#c62828")))
    `(:t "column"
      :spacing 8
      :children
      [(:t "text" :text "EBP milestone" :style "title")
       (:t "text" :text ,(if on "Flag: on" "Flag: off") :style "body")
       (:t "canvas" :id "indicator" :width 240 :height 120
        :ops [(:op "rect" :x 0 :y 0 :width 240 :height 120 :fill ,fill)
              (:op "rect" :x 8 :y 8 :width 224 :height 104
               :color "#ffffff" :stroke_width 2)])
       (:t "button" :id "set-on" :label "Set on" :enabled t
        :on_tap ,(ebpm1--descriptor t))
       (:t "button" :id "set-off" :label "Set off" :enabled t
        :on_tap ,(ebpm1--descriptor nil))])))

(defun ebpm1-publish (&optional client)
  "Push the current screen through CLIENT, default `ebpm1-client'.
Only a session in `syncing' or `ready' can carry a surface update;
otherwise nothing is sent.  Value is the revision used, or nil.  A
failure to reserve or send the revision is reported as a message."
  (let ((client (or client ebpm1-client)))
    (when (and client (memq (ebp-client-state client) '(syncing ready)))
      (condition-case err
          (ebp-client-surface-update client ebpm1-surface
                                     (ebpm1-spec (ebpm1-flag)))
        (error
         (message "ebpm1: publish failed: %s" (error-message-string err))
         nil)))))

;;;; The action

(defun ebpm1--handle-flag-set (_client params)
  "The `milestone.flag-set' handler: validate PARAMS, commit, answer.
A wrong surface, a missing or negative `revision_seen', or a `desired'
argument that is not exactly a JSON Boolean is permanently `rejected'.
Replay against an older revision is explicitly accepted: a set remains
meaningful at any revision the user saw.  The flag is committed before
`accepted' is returned; the next screen is published afterwards from a
timer, never from inside the dispatch.  A failed write answers
`ebpm1-storage-failure-status'."
  (let ((surface (plist-get params :surface))
        (revision (plist-get params :revision_seen))
        (desired (plist-get (plist-get params :args) :desired)))
    (cond
     ((not (equal surface ebpm1-surface)) 'rejected)
     ((not (and (integerp revision) (>= revision 0))) 'rejected)
     ((not (memq desired '(t :json-false))) 'rejected)
     (t
      (let ((value (eq desired t)))
        (if (condition-case err
                (progn (ebpm1-flag-set value) t)
              (error
               (message "ebpm1: flag write failed, answering retryable (%s)"
                        (error-message-string err))
               nil))
            (progn
              (run-hook-with-args 'ebpm1-after-accept-functions value)
              (run-at-time 0 nil #'ebpm1-publish)
              'accepted)
          ebpm1-storage-failure-status))))))

;;;; Session lifecycle

(defun ebpm1--before-replay (client)
  "SPEC 10.3 step 3: publish the screen before the replay barrier."
  (ebpm1-publish client))

(defun ebpm1--on-ready (client)
  "Report READY to `ebpm1-state-functions'."
  (run-hook-with-args 'ebpm1-state-functions client 'ready))

(defun ebpm1-app-start (host port pairing-id token &rest config)
  "Connect to the Companion at HOST:PORT as PAIRING-ID with TOKEN.
TOKEN is the 22-character display form provisioned at pairing; it is
decoded here and never logged.  CONFIG is passed through to
`ebp-connect' (for example :receipt-file or :auto-reconnect).  The
returned client is also stored in `ebpm1-client'; a previous client is
closed first."
  (when ebpm1-client
    (ebpm1-app-stop))
  (let ((client (apply #'ebp-connect host port
                       :client-name "ebpm1"
                       :client-version "0.1.0"
                       :pairing-id pairing-id
                       :token (ebp-decode-pairing-token token)
                       :wants nil
                       :before-replay-function #'ebpm1--before-replay
                       :ready-function #'ebpm1--on-ready
                       config)))
    (ebp-client-register-action client ebpm1-action #'ebpm1--handle-flag-set)
    (setq ebpm1-client client)
    (run-hook-with-args 'ebpm1-state-functions client 'connecting)
    client))

(defun ebpm1-app-stop ()
  "Close the client and the flag database."
  (when ebpm1-client
    (let ((client ebpm1-client))
      (setq ebpm1-client nil)
      (ignore-errors (ebp-client-close client 'ebpm1-stop))
      (run-hook-with-args 'ebpm1-state-functions client 'closed)))
  (ebpm1-flag-close))

(provide 'ebpm1-app)
;;; ebpm1-app.el ends here
