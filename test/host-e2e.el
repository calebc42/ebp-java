;;; host-e2e.el --- batch driver: the real ebp.el peer for the host e2e test -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; Started by test/host_e2e.py with the Companion's port and credentials
;; in the environment.  Runs the unmodified milestone application
;; (`ebpm1-app') over the unmodified ebp.el client against the Java
;; Companion, appends every state transition and accepted flag value to
;; EBPM1_LOG, and exits when EBPM1_STOP_FILE appears.  The token arrives
;; through the environment of a test process the harness owns; it is
;; never written to the log.

;;; Code:

(require 'ebp)
(require 'ebpm1-app)

(defvar ebpm1-e2e--log-file (getenv "EBPM1_LOG"))

(defun ebpm1-e2e--log (fmt &rest args)
  "Append FMT formatted with ARGS to the harness log."
  (with-temp-buffer
    (insert (apply #'format fmt args) "\n")
    (append-to-file (point-min) (point-max) ebpm1-e2e--log-file)))

(let* ((dir (file-name-as-directory (getenv "EBPM1_DIR")))
       ;; EBPM1_TRANSPORT selects the SPEC 5 profile: "tcp" (default,
       ;; EBPM1_PORT), "unix" (SPEC 5.3, dial EBPM1_SOCKET), or "listen"
       ;; (SPEC 5.6, bind EBPM1_SOCKET and let the Companion dial).
       (transport (or (getenv "EBPM1_TRANSPORT") "tcp"))
       (port (and (getenv "EBPM1_PORT") (string-to-number (getenv "EBPM1_PORT"))))
       (socket (getenv "EBPM1_SOCKET"))
       (pairing (getenv "EBPM1_PAIRING"))
       (token (getenv "EBPM1_TOKEN"))
       (stop-file (getenv "EBPM1_STOP_FILE"))
       (receipts (expand-file-name "receipts.sqlite" dir)))
  (make-directory dir t)
  (setq ebp-directory dir)
  (setq ebpm1-flag-file (expand-file-name "flag.sqlite" dir))
  (add-hook 'ebpm1-after-accept-functions
            (lambda (value) (ebpm1-e2e--log "accept %S" value)))
  (add-hook 'ebpm1-state-functions
            (lambda (_client state) (ebpm1-e2e--log "state %S" state)))
  (pcase transport
    ("tcp" (ebpm1-app-start "127.0.0.1" port pairing token :receipt-file receipts))
    ("unix" (ebpm1-app-start socket nil pairing token :receipt-file receipts
                             :transport 'loopback-unix))
    ("listen" (require 'ebp-unix)
     (ebpm1-app-start socket nil pairing token :receipt-file receipts
                      :transport 'emacs-unix))
    (_ (error "unknown EBPM1_TRANSPORT %S" transport)))
  (ebpm1-e2e--log "transport %s" transport)
  (ebpm1-e2e--log "started flag=%S" (ebpm1-flag))
  (while (not (file-exists-p stop-file))
    (accept-process-output nil 0.2))
  (ebpm1-e2e--log "stopping flag=%S" (ebpm1-flag))
  (ebpm1-app-stop)
  (kill-emacs 0))

;;; host-e2e.el ends here
