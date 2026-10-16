// Shows the backend image when there is one, otherwise a lettered placeholder.
export function MediaImage({ src, alt, label = alt }) {
  if (src) {
    return <img src={src} alt={alt} />;
  }

  return (
    <div className="image-fallback" aria-hidden="true">
      <span>{label?.slice(0, 2).toUpperCase() || "LF"}</span>
    </div>
  );
}
