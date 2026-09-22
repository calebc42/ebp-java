;;; ebpm1-android.el --- Android Emacs renderer for the EBP milestone -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The Android half of the M1 milestone: an Emacs buffer that presents
;; the screen the in-process Java Companion accepted, and routes button
;; activations back to it.  It talks to the Companion only through the
;; jetpacs-native fork's `android-ebp-*' primitives and never reads
;; application state: what it shows is one committed presentation
;; generation fetched as a single bundle.
;;
;; Touch and keyboard share one path.  Every enabled button is a text
;; button whose `action' calls `android-ebp-activate'; mouse-1 (which
;; is what a tap becomes) follows the link, and RET pushes the button.
;;
;; Invalidations arrive as `jetpacs-event' events on `special-event-map'
;; with payloads beginning "ebp:".  Other payloads are passed to the
;; binding that was installed before this one.

;;; Code:

(require 'button)
(require 'ebpm1-app)

(declare-function android-ebp-start "androidfns.c" (&optional port))
(declare-function android-ebp-stop "androidfns.c" ())
(declare-function android-ebp-credentials "androidfns.c" ())
(declare-function android-ebp-status "androidfns.c" ())
(declare-function android-ebp-snapshot "androidfns.c" (surface))
(declare-function android-ebp-activate "androidfns.c" (surface revision path))
(declare-function canvas-refresh "image.c" (image &optional reload-data))

(defconst ebpm1-android-buffer "*ebp-milestone*"
  "The buffer presenting the milestone screen.")

(defvar ebpm1-android--port nil
  "The port the Companion endpoint is listening on, or nil.")

(defvar ebpm1-android--canvases (make-hash-table :test #'equal)
  "Presentation path -> the live canvas image spec for that path.
A repaint replaces the spec's pixels in place and refreshes it, so the
canvas keeps its identity across generations instead of leaking one
canvas object per refresh.")

(defvar ebpm1-android--previous-jetpacs-binding nil
  "The `special-event-map' binding for `jetpacs-event' before ours.")

(defvar ebpm1-android--installed nil
  "Non-nil while our `jetpacs-event' binding is installed.")

(defvar-keymap ebpm1-android-mode-map
  :doc "Keymap for `ebpm1-android-mode'."
  :parent special-mode-map
  "TAB" #'forward-button
  "<backtab>" #'backward-button
  "g" #'ebpm1-android-refresh
  "s" #'ebpm1-android-show-status)

(define-derived-mode ebpm1-android-mode special-mode "EBP-M1"
  "Major mode for the milestone screen buffer.
TAB and S-TAB move between buttons, RET or a tap activates one, `g'
refetches the published presentation, and `s' shows the endpoint's
diagnostics."
  (setq-local cursor-type t)
  (setq-local truncate-lines nil))

(defun ebpm1-android--available-p ()
  "Non-nil when this Emacs has the fork's EBP bridge primitives."
  (fboundp 'android-ebp-start))

(defun ebpm1-android--require ()
  "Signal a `user-error' unless the EBP bridge is available."
  (unless (ebpm1-android--available-p)
    (user-error "This Emacs has no EBP host bridge (android-ebp-start)")))

;;;; Lifecycle

;;;###autoload
(defun ebpm1-android-start ()
  "Start the in-process Companion, connect the application, show the screen."
  (interactive)
  (ebpm1-android--require)
  (let ((port (android-ebp-start 0)))
    (unless port
      (user-error "The EBP Companion endpoint could not be started"))
    (setq ebpm1-android--port port)
    (let ((credentials (android-ebp-credentials)))
      (unless (and (stringp credentials)
                   (string-match
                    "\\`\\([0-9a-f]\\{32\\}\\):\\([A-Za-z0-9_-]\\{22\\}\\)\\'"
                    credentials))
        (user-error "The EBP Companion did not provide a well-formed pairing"))
      ;; The token is a secret: it goes to the client and nowhere else.
      (ebpm1-app-start "127.0.0.1" port
                       (match-string 1 credentials)
                       (match-string 2 credentials))))
  (ebpm1-android--install-event-handler)
  (with-current-buffer (get-buffer-create ebpm1-android-buffer)
    (unless (derived-mode-p 'ebpm1-android-mode)
      (ebpm1-android-mode))
    (pop-to-buffer (current-buffer)))
  (ebpm1-android-refresh))

(defun ebpm1-android-stop ()
  "Disconnect the application, stop the Companion, and forget the screen."
  (interactive)
  (ebpm1-android--uninstall-event-handler)
  (ebpm1-app-stop)
  (when (fboundp 'android-ebp-stop)
    (android-ebp-stop))
  (clrhash ebpm1-android--canvases)
  (setq ebpm1-android--port nil))

;;;; Invalidation events

(defun ebpm1-android--install-event-handler ()
  "Bind `jetpacs-event', remembering the binding that was there."
  (unless ebpm1-android--installed
    (setq ebpm1-android--previous-jetpacs-binding
          (lookup-key special-event-map [jetpacs-event]))
    (define-key special-event-map [jetpacs-event]
                #'ebpm1-android-handle-event)
    (setq ebpm1-android--installed t)))

(defun ebpm1-android--uninstall-event-handler ()
  "Restore the `jetpacs-event' binding that preceded ours."
  (when ebpm1-android--installed
    (define-key special-event-map [jetpacs-event]
                ebpm1-android--previous-jetpacs-binding)
    (setq ebpm1-android--installed nil
          ebpm1-android--previous-jetpacs-binding nil)))

(defun ebpm1-android-handle-event (event)
  "Handle the `jetpacs-event' EVENT; EBP payloads refresh the screen.
Any other payload goes to the binding installed before this one."
  (interactive "e")
  (let ((payload (nth 1 event)))
    (if (and (stringp payload) (string-prefix-p "ebp:" payload))
        (ebpm1-android--dispatch payload)
      (let ((previous ebpm1-android--previous-jetpacs-binding))
        (when (and (functionp previous)
                   (not (eq previous #'ebpm1-android-handle-event)))
          (funcall previous event))))))

(defun ebpm1-android--dispatch (payload)
  "Act on the EBP invalidation PAYLOAD."
  (cond
   ((string-prefix-p "ebp:surface:" payload)
    (when (equal (substring payload (length "ebp:surface:")) ebpm1-surface)
      (ebpm1-android-refresh)))
   ((equal payload "ebp:settings")
    (display-buffer ebpm1-android-buffer)
    (ebpm1-android-refresh))
   (t
    ;; ebp:session:*, ebp:queue, ebp:diag: the header changed.
    (ebpm1-android-refresh))))

;;;; Rendering

(defun ebpm1-android--parse (json)
  "Parse the JSON document string JSON into plists, or return nil."
  (and (stringp json)
       (condition-case nil
           (json-parse-string json :object-type 'plist :array-type 'list
                              :false-object :json-false :null-object nil)
         (error nil))))

(defun ebpm1-android--yes-no (value)
  "Render the JSON Boolean VALUE."
  (cond ((eq value t) "yes") ((eq value :json-false) "no") (t "-")))

(defun ebpm1-android-refresh ()
  "Refetch the published presentation and repaint the screen buffer."
  (interactive)
  (ebpm1-android--require)
  (let* ((status (ebpm1-android--parse (android-ebp-status)))
         (bundle (android-ebp-snapshot ebpm1-surface))
         (snapshot (and bundle (ebpm1-android--parse (car bundle))))
         (pixels (and bundle (cdr bundle))))
    (with-current-buffer (get-buffer-create ebpm1-android-buffer)
      (unless (derived-mode-p 'ebpm1-android-mode)
        (ebpm1-android-mode))
      (let ((inhibit-read-only t)
            (anchor (ebpm1-android--anchor)))
        (erase-buffer)
        (ebpm1-android--insert-header status snapshot)
        (if (and snapshot (eq (plist-get snapshot :present) t))
            (ebpm1-android--insert-items snapshot pixels)
          (insert "No published screen yet.\n"))
        (ebpm1-android--restore-anchor anchor)))))

(defun ebpm1-android-show-status ()
  "Show the endpoint's diagnostics document in the echo area."
  (interactive)
  (ebpm1-android--require)
  (message "%s" (or (android-ebp-status) "EBP endpoint not running")))

(defun ebpm1-android--insert-header (status snapshot)
  "Insert the host diagnostics from STATUS above the authored screen.
SNAPSHOT supplies the displayed revision.  This is receiver-owned text,
not part of the authored document."
  (let ((start (point)))
    (insert (format "EBP Companion (Java, in-process)  port %s  session %s\n"
                    (or (plist-get status :port) ebpm1-android--port "?")
                    (or (plist-get status :session) "none")))
    (insert (format "revision %s  generation %s  queued %s  in-flight %s  pump %s\n"
                    (if snapshot (plist-get snapshot :revision) "-")
                    (or (plist-get status :generation) "-")
                    (or (plist-get status :queued_events) "-")
                    (ebpm1-android--yes-no (plist-get status :in_flight))
                    (if (eq (plist-get status :pump_paused) t) "paused" "running")))
    (when-let* ((last-error (plist-get status :last_error)))
      (insert (format "last error: %s\n" last-error)))
    (add-text-properties start (point) '(face shadow ebpm1-header t))
    (insert "\n")))

(defun ebpm1-android--insert-items (snapshot pixels)
  "Insert the presentation items of SNAPSHOT; PIXELS holds the rasters."
  (let ((revision (plist-get snapshot :revision)))
    (dolist (item (plist-get snapshot :items))
      (pcase (plist-get item :kind)
        ("text" (ebpm1-android--insert-text item))
        ("button" (ebpm1-android--insert-button item revision))
        ("canvas" (ebpm1-android--insert-canvas item pixels))
        (kind (insert (format "[unsupported item %s]\n" kind)))))))

(defun ebpm1-android--insert-text (item)
  "Insert the text ITEM as one line, styled by its `style'."
  (let ((face (pcase (plist-get item :style)
                ((or "title" "headline") 'bold)
                ((or "caption" "label") 'shadow)
                ("mono" 'fixed-pitch)
                (_ nil)))
        (text (or (plist-get item :text) "")))
    (insert (if face (propertize text 'face face) text) "\n")))

(defun ebpm1-android--insert-button (item revision)
  "Insert the button ITEM, displayed at REVISION, as a text button.
A disabled button is plain shadowed text with no action."
  (let ((label (format "[ %s ]" (or (plist-get item :label) "")))
        (path (plist-get item :path)))
    (if (eq (plist-get item :enabled) t)
        (insert-text-button label
                            'action #'ebpm1-android--button-action
                            'follow-link t
                            'help-echo "Activate"
                            'ebpm1-path path
                            'ebpm1-revision revision)
      (insert (propertize label 'face 'shadow 'ebpm1-path path)))
    (insert "\n")))

(defun ebpm1-android--button-action (button)
  "Activate BUTTON through the Companion."
  (ebpm1-android--activate (button-get button 'ebpm1-path)
                           (button-get button 'ebpm1-revision)))

(defun ebpm1-android--activate (path revision)
  "Ask the Companion to activate the button PATH displayed at REVISION."
  (pcase (android-ebp-activate ebpm1-surface revision path)
    (0 (message "ebpm1: activation submitted"))
    (1 (message "ebpm1: the activation queue is saturated; try again"))
    (2 (message "ebpm1: the Companion endpoint is not running"))
    (3 (message "ebpm1: the Companion refused the activation arguments"))
    (other (message "ebpm1: unexpected activation result %S" other))))

(defun ebpm1-android--insert-canvas (item pixels)
  "Insert the canvas ITEM whose raster lives in PIXELS.
A raster whose size disagrees with the declared dimensions is skipped
with a diagnostic line rather than handed to the image type."
  (let* ((path (plist-get item :path))
         (width (plist-get item :width))
         (height (plist-get item :height))
         (offset (plist-get item :raster_offset))
         (bytes (plist-get item :raster_bytes)))
    (if (not (and (natnump width) (natnump height) (> width 0) (> height 0)
                  (natnump offset) (natnump bytes)
                  (= bytes (* 4 width height))
                  (stringp pixels)
                  (<= (+ offset bytes) (length pixels))))
        (insert (propertize (format "[canvas %s: raster mismatch]" path)
                            'face 'error)
                "\n")
      (let ((data (substring pixels offset (+ offset bytes)))
            (spec (gethash path ebpm1-android--canvases)))
        (if (null spec)
            (progn
              (setq spec (list 'image :type 'canvas
                               :id (intern (format "ebpm1-canvas-%s" path))
                               :data-width width :data-height height
                               :data data))
              (puthash path spec ebpm1-android--canvases))
          ;; Same identity, new generation: replace the pixels in place
          ;; and let the canvas reload them.
          (plist-put (cdr spec) :data-width width)
          (plist-put (cdr spec) :data-height height)
          (plist-put (cdr spec) :data data)
          (when (fboundp 'canvas-refresh)
            (canvas-refresh spec t)))
        (insert (propertize " " 'display spec 'ebpm1-path path) "\n")))))

;;;; Point preservation across repaints

(defun ebpm1-android--anchor ()
  "Return (PATH . POINT) describing where point is before a repaint."
  (cons (get-text-property (point) 'ebpm1-path) (point)))

(defun ebpm1-android--find-path (path)
  "Return the first buffer position carrying `ebpm1-path' PATH, or nil."
  (let ((pos (point-min)) found)
    (while (and (not found) pos (< pos (point-max)))
      (if (equal (get-text-property pos 'ebpm1-path) path)
          (setq found pos)
        (setq pos (next-single-property-change pos 'ebpm1-path))))
    found))

(defun ebpm1-android--restore-anchor (anchor)
  "Move point back to the item ANCHOR named, or as close as possible."
  (let ((found (and (car anchor) (ebpm1-android--find-path (car anchor)))))
    (goto-char (or found (min (cdr anchor) (point-max))))))

(provide 'ebpm1-android)
;;; ebpm1-android.el ends here
