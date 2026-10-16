import { formatCurrency } from "../lib/format";
import { MediaImage } from "./MediaImage";

export function MenuCard({ item, restaurantId, signedIn, quantity, pending, onAdd, onDecrease }) {
  return (
    <article className="menu-card">
      <div className="menu-card__media">
        <MediaImage src={item.image_url} alt={item.name} />
        <span>{formatCurrency(item.price)}</span>
      </div>
      <div className="menu-card__body">
        <div>
          <p className="menu-card__meta">
            Item #{item.id} · Restaurant #{item.restaurant_id || restaurantId}
          </p>
          <h3>{item.name}</h3>
          <p>{item.description || "No description provided by the backend."}</p>
        </div>

        <div className="menu-actions">
          {signedIn && quantity > 0 ? (
            <div className="stepper" aria-label={`${item.name} quantity`}>
              <button
                type="button"
                disabled={pending}
                onClick={() => onDecrease(item.id)}
                aria-label={`Decrease ${item.name}`}
              >
                -
              </button>
              <strong>{quantity}</strong>
              <button
                type="button"
                disabled={pending}
                onClick={() => onAdd(item.id)}
                aria-label={`Increase ${item.name}`}
              >
                +
              </button>
            </div>
          ) : (
            <button
              className="primary-button"
              type="button"
              disabled={pending}
              onClick={() => onAdd(item.id)}
            >
              {signedIn ? "Add to cart" : "Sign in to add"}
            </button>
          )}
        </div>
      </div>
    </article>
  );
}
