;; Generate a 256px circular crop of resources/nido-icon.png: the logo is zoomed
;; (navy border cropped away) and masked into an anti-aliased disc with
;; transparent corners.
;;
;; Two assets are cut from the one source at different zooms:
;;   - resources/favicon.png   (margin 0.60) — browser tab; zoomed hard so the
;;                              eagle fills the disc and reads at ~16px.
;;   - resources/nido-logo.png (margin 0.82) — the dashboard rail home-button
;;                              mark; looser, keeping the gold ring as framing.
;;
;; Run from the repo root with the full JVM (needs java.awt, which Babashka
;; lacks). Regenerate both:
;;   clojure -M scripts/gen-favicon.clj 0.60 resources/favicon.png
;;   clojure -M scripts/gen-favicon.clj 0.82 resources/nido-logo.png
(import '[javax.imageio ImageIO]
        '[java.awt.image BufferedImage]
        '[java.awt RenderingHints AlphaComposite Color]
        '[java.awt.geom Ellipse2D$Double]
        '[java.io File])

(def src-path "resources/nido-icon.png")
(def out-size 256)

;; Crop margin around the logo's bright bounding box, then inscribe the disc.
;; 1.0 = the gold ring sits exactly on the disc edge; <1.0 zooms in further so
;; the eagle reads larger (~0.60 fills the disc with just the eagle/N — about as
;; tight as it goes before the beak/N-foot clip); >1.0 leaves navy breathing-room.
(def margin (if-let [a (first *command-line-args*)] (Double/parseDouble a) 0.60))
(def out-path (or (second *command-line-args*) "resources/favicon.png"))

(def src (ImageIO/read (File. src-path)))
(def w (.getWidth src))
(def h (.getHeight src))

;; --- find bounding box of bright (non-navy) content -----------------------
(def lum-thresh 80)

(defn lum [argb]
  (let [r (bit-and (bit-shift-right argb 16) 0xff)
        g (bit-and (bit-shift-right argb 8) 0xff)
        b (bit-and argb 0xff)]
    (+ (* 0.299 r) (* 0.587 g) (* 0.114 b))))

(let [px (.getRGB src 0 0 w h (int-array (* w h)) 0 w)]
  (loop [i 0 minx w miny h maxx 0 maxy 0]
    (if (< i (* w h))
      (let [x (mod i w) y (quot i w)]
        (if (> (lum (aget px i)) lum-thresh)
          (recur (inc i) (min minx x) (min miny y) (max maxx x) (max maxy y))
          (recur (inc i) minx miny maxx maxy)))
      (let [cx (/ w 2.0) cy (/ h 2.0)
            content-half (max (- cx minx) (- maxx cx) (- cy miny) (- maxy cy))
            crop-half (* content-half margin)
            crop (int (* 2 crop-half))
            off (int (- cx crop-half))]
        (println "image" w "x" h)
        (println "content bbox" [minx miny maxx maxy] "content-half" content-half)
        (println "crop" crop "offset" off "(margin" margin ")")
        (def crop-info {:crop crop :off off})))))

;; --- render: zoom (crop) + circular anti-aliased mask ---------------------
(let [{:keys [crop off]} crop-info
      sub (.getSubimage src off off crop crop)
      out (BufferedImage. out-size out-size BufferedImage/TYPE_INT_ARGB)
      g (.createGraphics out)]
  (doto g
    (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setRenderingHint RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BICUBIC)
    (.setRenderingHint RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY))
  ;; 1. paint an anti-aliased white disc (becomes the alpha mask)
  (.setColor g Color/WHITE)
  (.fill g (Ellipse2D$Double. 0.0 0.0 (double out-size) (double out-size)))
  ;; 2. draw the zoomed logo only where the disc is opaque (AA edge preserved)
  (.setComposite g AlphaComposite/SrcIn)
  (.drawImage g sub 0 0 out-size out-size nil)
  (.dispose g)
  (ImageIO/write out "png" (File. out-path))
  (println "wrote" out-path out-size "x" out-size "ARGB circular"))
