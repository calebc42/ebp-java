;;; ebpm1-app-test.el --- ERT for the milestone application -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; Batch tests for `ebpm1-app.el': the authored document stays inside
;; the Companion's advertised `members' profile, the action handler
;; validates and commits as SPEC 14.4 requires, the flag survives
;; reopening its file, and a storage failure answers retryable rather
;; than permanent.  Every test uses a temporary flag file.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebpm1-app)

(defmacro ebpm1-test--with-flag (&rest body)
  "Run BODY with the flag stored in a fresh temporary file."
  (declare (indent 0))
  `(let* ((dir (make-temp-file "ebpm1-test-" t))
          (ebpm1-flag-file (expand-file-name "flag.sqlite" dir))
          (ebpm1-client nil)
          (ebpm1-after-accept-functions nil))
     (ebpm1-flag-close)
     (unwind-protect
         (progn ,@body)
       (ebpm1-flag-close)
       (delete-directory dir t))))

(defun ebpm1-test--params (&rest overrides)
  "A valid `event.action' params plist, with OVERRIDES applied."
  (let ((params (list :event_id "00112233445566778899aabbccddeeff"
                      :action ebpm1-action
                      :args '(:desired t)
                      :surface ebpm1-surface
                      :revision_seen 3
                      :occurred_at_ms 1)))
    (while overrides
      (setq params (plist-put params (car overrides) (cadr overrides)))
      (setq overrides (cddr overrides)))
    params))

(defun ebpm1-test--members (plist)
  "The member names of the JSON object PLIST, as strings."
  (let (keys)
    (while plist
      (push (substring (symbol-name (car plist)) 1) keys)
      (setq plist (cddr plist)))
    (nreverse keys)))

(defun ebpm1-test--check-node (node)
  "Assert NODE and its descendants author only advertised members."
  (let* ((type (plist-get node :t))
         (nodes (alist-get 'nodes ebpm1-advertised-members))
         (universal (alist-get 'universal ebpm1-advertised-members))
         (node-members (cdr (assoc type nodes))))
    (should (member type ebpm1-advertised-node-types))
    (should node-members)
    (dolist (member (ebpm1-test--members node))
      (unless (equal member "t")
        (should (or (member member node-members)
                    (member member universal)))))
    (when-let* ((tap (plist-get node :on_tap)))
      (should (equal (plist-get tap :action) ebpm1-action))
      (dolist (member (ebpm1-test--members tap))
        (should (member member '("action" "args" "when_offline" "ttl_s"))))
      (should (equal (plist-get tap :when_offline) "queue"))
      (should (integerp (plist-get tap :ttl_s)))
      (should (<= 1 (plist-get tap :ttl_s) 604800)))
    (mapc #'ebpm1-test--check-node (append (plist-get node :children) nil))))

(ert-deftest ebpm1-spec-authors-only-advertised-members ()
  "Every node type and member of the screen is advertised (SPEC 10.2)."
  (ebpm1-test--check-node (ebpm1-spec t))
  (ebpm1-test--check-node (ebpm1-spec nil)))

(ert-deftest ebpm1-spec-derives-the-screen-from-the-flag ()
  "The flag text and the Canvas fill both follow the flag."
  (let* ((on (ebpm1-spec t))
         (off (ebpm1-spec nil))
         (text (lambda (spec) (plist-get (aref (plist-get spec :children) 1)
                                         :text)))
         (fill (lambda (spec)
                 (plist-get (aref (plist-get
                                   (aref (plist-get spec :children) 2)
                                   :ops)
                                  0)
                            :fill))))
    (should (equal (funcall text on) "Flag: on"))
    (should (equal (funcall text off) "Flag: off"))
    (should-not (equal (funcall fill on) (funcall fill off)))
    ;; Both buttons remain enabled in both states: a repeated set is safe.
    (dolist (spec (list on off))
      (dolist (index '(3 4))
        (should (eq (plist-get (aref (plist-get spec :children) index)
                               :enabled)
                    t))))
    ;; The two descriptors carry opposite JSON Booleans.
    (should (eq (plist-get (plist-get (plist-get
                                       (aref (plist-get on :children) 3)
                                       :on_tap)
                                      :args)
                           :desired)
                t))
    (should (eq (plist-get (plist-get (plist-get
                                       (aref (plist-get on :children) 4)
                                       :on_tap)
                                      :args)
                           :desired)
                :json-false))))

(ert-deftest ebpm1-handler-rejects-a-wrong-surface ()
  (ebpm1-test--with-flag
    (should (eq (ebpm1--handle-flag-set
                 nil (ebpm1-test--params :surface "app:other"))
                'rejected))
    (should-not (ebpm1-flag))))

(ert-deftest ebpm1-handler-rejects-a-non-boolean-desired ()
  (ebpm1-test--with-flag
    (dolist (args '((:desired "true") (:desired 1) (:desired nil) nil
                    (:other t)))
      (should (eq (ebpm1--handle-flag-set nil (ebpm1-test--params :args args))
                  'rejected)))
    (should-not (ebpm1-flag))))

(ert-deftest ebpm1-handler-rejects-a-missing-or-bad-revision ()
  (ebpm1-test--with-flag
    (dolist (revision '(nil -1 "3" 2.0))
      (should (eq (ebpm1--handle-flag-set
                   nil (ebpm1-test--params :revision_seen revision))
                  'rejected)))
    (should-not (ebpm1-flag))))

(ert-deftest ebpm1-handler-accepts-and-persists-both-values ()
  (ebpm1-test--with-flag
    (should (eq (ebpm1--handle-flag-set nil (ebpm1-test--params)) 'accepted))
    (should (eq (ebpm1-flag) t))
    (should (eq (ebpm1--handle-flag-set
                 nil (ebpm1-test--params :args '(:desired :json-false)))
                'accepted))
    (should-not (ebpm1-flag))))

(ert-deftest ebpm1-handler-is-idempotent ()
  "Setting the same value twice is accepted twice and changes nothing."
  (ebpm1-test--with-flag
    (should (eq (ebpm1--handle-flag-set nil (ebpm1-test--params)) 'accepted))
    (should (eq (ebpm1--handle-flag-set nil (ebpm1-test--params)) 'accepted))
    (should (eq (ebpm1-flag) t))))

(ert-deftest ebpm1-handler-accepts-replay-at-an-older-revision ()
  "SPEC 14.5: a set is meaningful against newer state; replay is accepted."
  (ebpm1-test--with-flag
    (should (eq (ebpm1--handle-flag-set
                 nil (ebpm1-test--params :revision_seen 9
                                         :args '(:desired :json-false)))
                'accepted))
    (should (eq (ebpm1--handle-flag-set
                 nil (ebpm1-test--params :revision_seen 1))
                'accepted))
    (should (eq (ebpm1-flag) t))))

(ert-deftest ebpm1-flag-survives-reopening-its-file ()
  (ebpm1-test--with-flag
    (should (ebpm1-flag-set t))
    (ebpm1-flag-close)
    (should (eq (ebpm1-flag) t))
    (should-not (ebpm1-flag-set nil))
    (ebpm1-flag-close)
    (should-not (ebpm1-flag))))

(ert-deftest ebpm1-handler-answers-retryable-on-a-storage-failure ()
  "A failed write answers the documented non-status symbol, not `rejected'."
  (ebpm1-test--with-flag
    (let ((hook-ran nil))
      (add-hook 'ebpm1-after-accept-functions (lambda (_v) (setq hook-ran t)))
      (cl-letf (((symbol-function 'ebpm1-flag-set)
                 (lambda (_desired) (error "disk full"))))
        (should (eq (ebpm1--handle-flag-set nil (ebpm1-test--params))
                    ebpm1-storage-failure-status)))
      (should-not hook-ran)
      (should-not (memq ebpm1-storage-failure-status
                        '(accepted stale rejected))))))

(ert-deftest ebpm1-after-accept-hook-receives-the-committed-value ()
  (ebpm1-test--with-flag
    (let ((seen nil))
      (add-hook 'ebpm1-after-accept-functions (lambda (v) (push v seen)))
      (ebpm1--handle-flag-set nil (ebpm1-test--params))
      (ebpm1--handle-flag-set nil (ebpm1-test--params
                                   :args '(:desired :json-false)))
      (should (equal seen '(nil t))))))

(ert-deftest ebpm1-publish-is-a-no-op-without-a-client ()
  (ebpm1-test--with-flag
    (should-not (ebpm1-publish))))

(ert-deftest ebpm1-android-loads-on-a-host-without-the-bridge ()
  "The renderer loads without the fork's primitives and refuses to start."
  (require 'ebpm1-android)
  (should (fboundp 'ebpm1-android-start))
  (unless (fboundp 'android-ebp-start)
    (should-error (ebpm1-android-start) :type 'user-error)
    (should-error (ebpm1-android-refresh) :type 'user-error)))

(provide 'ebpm1-app-test)
;;; ebpm1-app-test.el ends here
