(ns canvas.integration.transcribe
  "Self-spec: `nido.transcribe.*` — video URL to transcript.

   A redef seam at the boundary, pure normalisation above it, and nothing that knows what a
   workstream is — which is what makes an adapter replaceable."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Transcript
  "A video's transcript as VTT, and where it came from — a published transcript or a local
   whisper run."
  [:map [:ok? :boolean]
        [:vtt-path {:optional true} [:maybe :string]]
        [:source {:optional true} [:maybe :keyword]]])

(Module transcribe-core
  "URL to VTT: dispatch a video to whichever transcriber can handle it."
  {:child [Transcript]}
  (Operation download-to-temp! "Fetch a URL to a temp file."
    {:signature [:=> [:catn [:url :string]] :map]})
  (Operation video!
    "Transcribe a video URL. A published transcript is preferred and whisper is the fallback,
     because the published one is free and exact where whisper is neither."
    {:signature [:=> [:catn [:opts :map]] Transcript]
     :delegates [download-to-temp!]}))

(Module transcribe-loom
  "The Loom public GraphQL client. No auth — these are public share URLs."
  (Operation http-request "One HTTP call, wrapped so tests can stub the network."
    {:signature [:=> [:catn [:method :keyword] [:url :string] [:opts :map]] :map]})
  (Operation extract-video-id "The video id in a share or embed URL, or nil."
    {:signature [:=> [:catn [:url :string]] [:maybe :string]]})
  (Operation fetch-vtt "A public video's published transcript."
    {:signature [:=> [:catn [:video-id :string]] :map] :delegates [http-request]})
  (Operation video-source-url
    "The CDN URL behind a video id — what the whisper fallback needs when a video has its
     transcript disabled."
    {:signature [:=> [:catn [:video-id :string]] [:maybe :string]] :delegates [http-request]}))

(Module transcribe-whisper
  "Shell out to the whisper CLI. The fallback, used when no transcript was published."
  (Operation sh! "Shell out with a timeout. A redef seam, so tests stub the subprocess entirely."
    {:signature [:=> [:catn [:args :any] [:opts :map]] :map]})
  (Operation run! "Transcribe a local file."
    {:signature [:=> [:catn [:opts :map]] :map] :delegates [sh!]}))
