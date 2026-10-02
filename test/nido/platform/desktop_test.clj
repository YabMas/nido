(ns nido.platform.desktop-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.platform.desktop :as desktop]))

(deftest script-escapes-quotes-backslashes-and-newlines
  (is (= "display notification \"say \\\"hi\\\" a\\\\b c\" with title \"t\""
         (desktop/notification-script {:message "say \"hi\" a\\b\nc" :title "t"}))))

(deftest script-carries-only-the-fields-given
  (is (= "display notification \"m\" with title \"t\" subtitle \"s\" sound name \"Glass\""
         (desktop/notification-script {:message "m" :title "t" :subtitle "s" :sound "Glass"})))
  (is (= "display notification \"m\""
         (desktop/notification-script {:message "m"}))))

(deftest notifier-args-pass-fields-and-guard-option-lookalikes
  (is (= ["-message" "m" "-title" "t" "-subtitle" "s" "-sound" "Glass"]
         (desktop/notifier-args {:message "m" :title "t" :subtitle "s" :sound "Glass"})))
  (is (= ["-message" "\\-1 failing" "-title" "\\[x]"]
         (desktop/notifier-args {:message "-1 failing" :title "[x]"}))))
