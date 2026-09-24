import type { MessageSummary } from "@/lib/types";
import { formatDuration } from "./format";

// Media is fetched through /media, which adds the admin token server side.
// preload="metadata" and no autoplay avoid surprise audio.
export function MessagePreview({ message }: { message: MessageSummary }) {
  const src = message.asset_id ? `/media/${encodeURIComponent(message.asset_id)}` : null;
  const mime = message.mime ?? "";
  const duration = formatDuration(message.duration_ms);
  const removed = message.state === "deleted" || message.state === "removed";

  let media: React.ReactNode = null;
  if (src && !removed) {
    if (mime.startsWith("video/") || (!mime && message.kind === "video")) {
      media = <video className="media" controls preload="metadata" playsInline src={src} />;
    } else if (mime.startsWith("audio/") || (!mime && (message.kind === "voice" || message.kind === "audio"))) {
      media = <audio className="media-audio" controls preload="metadata" src={src} />;
    } else if (mime.startsWith("image/") || (!mime && message.kind === "photo")) {
      // eslint-disable-next-line @next/next/no-img-element -- private, uncached media; next/image would cache it.
      media = <img className="media" src={src} alt="Reported photo" />;
    } else {
      media = (
        <a href={src} target="_blank" rel="noreferrer">
          Open attachment ({mime || "unknown type"})
        </a>
      );
    }
  }

  return (
    <div className="preview">
      <div className="preview-meta">
        <span className="tag">{message.kind}</span>
        <span className={`tag ${message.state === "ready" ? "" : "tag-warn"}`}>{message.state}</span>
        {duration ? <span className="muted">{duration}</span> : null}
      </div>
      {message.text ? <p className="preview-text">{message.text}</p> : null}
      {media}
      {!message.text && !media ? <p className="muted">No preview available.</p> : null}
    </div>
  );
}
