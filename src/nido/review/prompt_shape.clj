;; src/nido/review/prompt_shape.clj
(ns nido.review.prompt-shape
  "What a record round's judge or amender was shown, read back off the prompt it was handed.

   Every record round writes its prompt verbatim beside its answer (`<label>-prompt.txt`), so the
   composition of what a judge read — how much was the record, how much its history, which stance
   and which checks — is on disk for every round already run. Reading it there, rather than
   stamping it as rounds run, means a change to a prompt is measurable against every round before
   it, and nothing on the judging path has to change to make it so.

   A prompt's sections are its capitalised headings (`THE CLAUSES — …`, `PROJECT STANCE — …`); a
   section runs to the next heading. The stance is fingerprinted by digest, so two runs judged
   under the same stance text read as one variant whatever else moved around it.

   A variant is the stance digest and the checks asked. `variants` puts each run under the variant
   its first judge round was shown, beside its outcome (`trend/figures`), which is what makes a
   prompt change something `bb nido:review:prompts` can look back on."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.trend :as trend]
   [nido.review.digest :as digest])
  (:import
   [java.time Instant LocalDate ZoneId]))

(def ^:private heading
  #"^[A-Z][A-Z0-9 ,'\-—:()/&.`]{6,}")

(defn- heading? [line]
  (and (re-find heading line)
       (<= 6 (count (filter #(Character/isUpperCase (char %)) (take 30 line))))))

(defn- heading-key
  "A heading's name: its words before the first dash or colon, so `CLAIMS — each with …` and
   `CLAIMS — …` are one section whatever follows."
  [line]
  (->> (str/split (first (str/split line #"[—:]")) #"\s+")
       (take-while #(not (re-find #"[a-z]" %)))
       (map #(str/replace % #"[^A-Z']" ""))
       (remove str/blank?)
       (take 5)
       (str/join " ")))

(defn ^{:malli/schema [:=> [:cat :string] [:vector [:map [:heading :string] [:text :string]]]]}
  sections
  "`prompt` cut at its headings, in order, each with its text. Text before the first heading is
   `(preamble)`."
  [prompt]
  (let [lines (str/split prompt #"\n" -1)]
    (loop [ls lines cur "(preamble)" buf [] out []]
      (if-let [l (first ls)]
        (if (heading? l)
          (recur (rest ls) (heading-key l) [l]
                 (cond-> out (seq buf) (conj {:heading cur :text (str/join "\n" buf)})))
          (recur (rest ls) cur (conj buf l) out))
        (cond-> out (seq buf) (conj {:heading cur :text (str/join "\n" buf)}))))))

(defn- checks-asked
  "The checks the DERIVE section defines, by the name each line opens with — up to the
   recommendations that follow them, whose lines open the same way."
  [secs]
  (when-let [d (some #(when (str/starts-with? (:heading %) "DERIVE")
                        (first (str/split (:text %) #"\nThen recommend")))
                     secs)]
    (vec (distinct (keep #(second (re-find #"^  ([a-z][a-z\-]+)\s+—" %)) (str/split-lines d))))))

(defn- stance-text
  "The stance document inside its section: from its `# ` title, without the framing each loop words
   its own way above it, to before the level reminder a baseline prompt closes on (`---`) — so the
   same stance is one variant in every loop that shows it."
  [section]
  (let [from (or (some-> (re-find #"(?m)^# .*" section) (->> (str/index-of section))) 0)]
    (str/trim (first (str/split (subs section from) #"\n---\n")))))

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  shape
  "`prompt`'s composition: `{:bytes :sections {heading bytes} :stance {:digest :bytes}|nil
   :checks [name …]|nil}`. A heading met twice sums."
  [prompt]
  (let [secs   (sections prompt)
        stance (some #(when (str/starts-with? (:heading %) "PROJECT STANCE") (:text %)) secs)]
    {:bytes    (count prompt)
     :sections (reduce (fn [m {:keys [heading text]}] (update m heading (fnil + 0) (count text))) {} secs)
     :stance   (when stance
                 {:digest (subs (digest/sha256-hex (stance-text stance)) 0 12)
                  :bytes  (count stance)})
     :checks   (checks-asked secs)}))

(defn- day-of [path]
  (str (LocalDate/ofInstant (Instant/ofEpochMilli (.toMillis (fs/last-modified-time path)))
                            (ZoneId/systemDefault))))

(defn- prompt-kind
  "The round a prompt file was written for, off its name: `design-decision-round-3-prompt.txt`
   is a `design-decision`."
  [file-name]
  (second (re-find #"^(.+?)-(?:round-\d+|\d+)(?:-.*)?-prompt\.txt$" file-name)))

(defn ^{:malli/schema [:=> [:cat :int] [:vector :map]]}
  read-recent!
  "Every record-loop prompt written in the last `days` days, as `{:run-id :kind :file :day :shape}`."
  [days]
  (let [cutoff (- (System/currentTimeMillis) (* (inc days) 86400000))]
    (into []
          (comp (filter #(re-find #"^(design|baseline)-loop-" (str (fs/file-name %))))
                (filter #(> (.toMillis (fs/last-modified-time %)) cutoff))
                (mapcat (fn [dir]
                          (for [f (fs/glob dir "*-prompt.txt")
                                :let [kind (prompt-kind (str (fs/file-name f)))]
                                :when kind]
                            {:run-id (str (fs/file-name dir)) :kind kind :file (str f)
                             :day (day-of f)})))
                (keep #(try (assoc % :shape (shape (slurp (:file %)))) (catch Exception _ nil))))
          (when (fs/exists? (cstate/runs-dir)) (fs/list-dir (cstate/runs-dir))))))

(def ^:private judge-kinds #{"design-decision" "baseline-review"})

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  variants
  "Each run under the variant its first judge round was shown — `{:stance <digest|nil> :checks
   [...]}` — with that run's figures (`trend/figures`), for runs that have finished."
  [prompts]
  (vec
   (for [[run-id ps] (group-by :run-id prompts)
         :let [first-judge (first (sort-by :file (filter (comp judge-kinds :kind) ps)))
               report      (fs/path (cstate/runs-dir) run-id "report.json")
               figs        (when (and first-judge (fs/exists? report))
                             (try (trend/figures (json/parse-string (slurp (str report))))
                                  (catch Exception _ nil)))]
         :when figs]
     (assoc figs :variant {:stance (get-in first-judge [:shape :stance :digest])
                           :checks (get-in first-judge [:shape :checks])}))))
