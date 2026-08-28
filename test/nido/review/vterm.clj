(ns nido.review.vterm
  "A terminal, small enough to assert against.

   The live block's correctness is a claim about a SCREEN — that one copy of the
   frame is on it — and no assertion over the bytes can make that claim. This
   consumes the bytes `frontend/repaint!` actually writes and answers what the
   terminal would be showing, so a test can count the copies.

   Models only what the live block emits: printable text, newline, `ESC[nA`
   (cursor up, clamped at the top of the screen, as a real terminal clamps it)
   and `ESC[0J` (clear to end of screen). It also models deferred wrap — writing
   the last column leaves the cursor there with a wrap PENDING rather than on
   the next row — because that is where the off-by-one at the right margin lives,
   and a model without it would report a bug the terminal does not have."
  (:require [clojure.string :as str]))

(def ^:private esc (char 27))

(defn make
  "A blank `cols` x `rows` terminal with the cursor home."
  [cols rows]
  {:cols cols :rows rows :row 0 :col 0 :pending-wrap false
   :screen     (vec (repeat rows (vec (repeat cols \space))))
   :scrollback []})

(defn- scroll
  "Push the top row into scrollback and open a blank one at the bottom."
  [t]
  (-> t
      (update :scrollback conj (str/trimr (apply str (first (:screen t)))))
      (update :screen #(conj (vec (rest %)) (vec (repeat (:cols t) \space))))
      (update :row dec)))

(defn- fit-row [t] (if (>= (:row t) (:rows t)) (scroll t) t))

(defn- put [t ch]
  (let [t (cond-> t
            (:pending-wrap t) (-> (assoc :col 0 :pending-wrap false)
                                  (update :row inc)
                                  fit-row))
        t (fit-row t)
        t (assoc-in t [:screen (:row t) (:col t)] ch)]
    (if (= (inc (:col t)) (:cols t))
      (assoc t :pending-wrap true)
      (update t :col inc))))

(defn- line-feed [t]
  (fit-row (-> t (assoc :col 0 :pending-wrap false) (update :row inc))))

(defn- cursor-up [t n]
  ;; Clamped at the top of the screen — the rows above are scrollback, and no
  ;; CUU reaches them. This clamp is the whole of the too-tall-frame bug.
  (assoc t :row (max 0 (- (:row t) n)) :pending-wrap false))

(defn- clear-to-end [t]
  (let [{:keys [cols row col]} t]
    (update t :screen
            #(vec (map-indexed
                   (fn [i r]
                     (cond (< i row) r
                           (= i row) (vec (concat (take col r) (repeat (- cols col) \space)))
                           :else     (vec (repeat cols \space))))
                   %)))))

(defn feed
  "Apply the bytes in `s` to terminal `t`."
  [t s]
  (loop [t t i 0]
    (if (>= i (count s))
      t
      (let [c (nth s i)]
        (cond
          (= c \newline) (recur (line-feed t) (inc i))
          (= c \return)  (recur (assoc t :col 0 :pending-wrap false) (inc i))
          (= c esc)
          (if-let [[whole params cmd] (re-find #"^\[([0-9;?]*)([A-Za-z])" (subs s (inc i)))]
            (recur (case cmd
                     "A" (cursor-up t (max 1 (parse-long (if (str/blank? params) "1" params))))
                     "J" (if (contains? #{"" "0"} params) (clear-to-end t) t)
                     t)
                   (+ i 1 (count whole)))
            (recur t (inc i)))
          :else (recur (put t c) (inc i)))))))

(defn screen
  "What the window is showing, as text."
  [t]
  (str/join "\n" (map #(str/trimr (apply str %)) (:screen t))))

(defn occurrences
  "How many rows anywhere — on screen or scrolled off it — contain `needle`.
   One is a live block being repainted; more is the block duplicating."
  [t needle]
  (count (filter #(str/includes? % needle)
                 (concat (:scrollback t) (map #(apply str %) (:screen t))))))
