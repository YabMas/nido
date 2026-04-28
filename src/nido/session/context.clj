(ns nido.session.context
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(defn resolve-ref
  "Resolve a dotted reference like \"pg.port\" against a context map.
   Returns nil if not found."
  [ctx ref-str]
  (let [parts (str/split ref-str #"\.")
        ks (map keyword parts)]
    (get-in ctx ks)))

(defn substitute-value
  "Substitute template references in a single value.
   - If the entire string is exactly one `{{ref}}`, returns the raw resolved value (preserving type).
   - If the string contains `{{ref}}` mixed with other text, returns a string with interpolation.
   - Non-string values pass through unchanged."
  [ctx v]
  (if-not (string? v)
    v
    (let [whole-ref-pattern #"^\{\{([^}]+)\}\}$"
          mixed-pattern #"\{\{([^}]+)\}\}"]
      (if-let [[_ ref] (re-matches whole-ref-pattern v)]
        ;; Entire string is one ref — preserve type
        (let [resolved (resolve-ref ctx ref)]
          (if (some? resolved)
            resolved
            v))
        ;; Mixed string — interpolate as string
        (str/replace v mixed-pattern
                     (fn [[_ ref]]
                       (let [resolved (resolve-ref ctx ref)]
                         (if (some? resolved)
                           (str resolved)
                           (str "{{" ref "}}")))))))))

(defn substitute
  "Walk an arbitrary data structure, substituting all `{{ref}}` templates
   against the context map."
  [ctx data]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (substitute-value ctx x)
       x))
   data))

(defn merge-context
  "Merge a service's contributions into the context under its name key.
   service-name is a keyword, contributions is a map."
  [ctx service-name contributions]
  (assoc ctx service-name contributions))

(defn prepare-jvm
  "Derive joined-string forms for JVM config so service-def templates can
   reference them directly without a walk-time join step.

   In:  {:heap-max \"2g\" :aliases [:dev :cider/nrepl] :extra-opts [\"-XX:+Foo\"]}
   Out: {:heap-max \"2g\" :aliases [...] :extra-opts [...]
         :aliases-joined \"dev:cider/nrepl\"
         :extra-opts-joined \"-XX:+Foo\"}

   Aliases are joined with ':' (the `-M:a:b:c` convention). Extra JVM opts
   are space-joined. Keys already present in the input map are preserved."
  [jvm]
  (let [aliases (seq (:aliases jvm))
        extra-opts (seq (:extra-opts jvm))]
    (assoc jvm
           :aliases-joined
           (if aliases
             (str/join ":"
                       (map (fn [a] (if (keyword? a) (subs (str a) 1) (str a)))
                            aliases))
             "")
           :extra-opts-joined
           (if extra-opts (str/join " " extra-opts) ""))))
