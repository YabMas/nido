(ns nido.coordinator.clock
  "Single seam for current-time reads across the coordinator. Tests
   `with-redefs` `now-iso` to fake time without poking each call site.")

(defn now-iso
  "Current instant as an ISO-8601 string. Single seam for tests."
  []
  (str (java.time.Instant/now)))
