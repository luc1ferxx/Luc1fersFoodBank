import type { MenuItem } from "../api/dto";
import { formatMoney } from "../lib/format";
import { Alert } from "./Alert";
import { MediaImage } from "./MediaImage";

interface MenuCardProps {
  item: MenuItem;
  signedIn: boolean;
  quantity: number;
  pending: boolean;
  /** Why this card's last add or decrease failed, shown on the card the customer used. */
  error?: string | undefined;
  onAdd: (item: MenuItem) => void;
  onDecrease: (menuItemId: number) => void;
}

export function MenuCard({
  item,
  signedIn,
  quantity,
  pending,
  error,
  onAdd,
  onDecrease,
}: MenuCardProps) {
  return (
    <article className="menu-card">
      <div className="menu-card__media">
        <MediaImage src={item.image_url} alt={item.name} />
        <span>{formatMoney(item.price, item.currency)}</span>
      </div>
      <div className="menu-card__body">
        <div>
          <p className="menu-card__meta">
            Item #{item.id} · Restaurant #{item.restaurant_id}
          </p>
          <h3>{item.name}</h3>
          <p>{item.description || "No description provided by the backend."}</p>
        </div>

        <Alert tone="danger">{error}</Alert>

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
                onClick={() => onAdd(item)}
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
              onClick={() => onAdd(item)}
            >
              {signedIn ? "Add to cart" : "Sign in to add"}
            </button>
          )}
        </div>
      </div>
    </article>
  );
}
