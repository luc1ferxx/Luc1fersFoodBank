import type { FormEvent } from "react";
import type { CartItem, Order } from "../api/dto";
import type { CartState } from "../hooks/useCart";
import type { CheckoutState } from "../hooks/useCheckout";
import { formatDateTime, formatMoney, lineTotal } from "../lib/format";
import { Alert } from "./Alert";
import { MediaImage } from "./MediaImage";

function CartLine({
  item,
  pending,
  onSetQuantity,
}: {
  item: CartItem;
  pending: boolean;
  onSetQuantity: (item: CartItem, quantity: number) => Promise<void>;
}) {
  return (
    <article className="cart-line">
      <div className="cart-line__media">
        <MediaImage src={item.menu_item_image_url} alt={item.menu_item_name} />
      </div>
      <div>
        <strong>{item.menu_item_name}</strong>
        <span>{formatMoney(item.price, item.currency)} each</span>
        <span>Subtotal {formatMoney(lineTotal(item.price, item.quantity), item.currency)}</span>
      </div>
      <div className="stepper cart-line__stepper">
        <button
          type="button"
          disabled={pending}
          onClick={() => onSetQuantity(item, item.quantity - 1)}
          aria-label={`Decrease ${item.menu_item_name}`}
        >
          -
        </button>
        <strong>{item.quantity}</strong>
        <button
          type="button"
          disabled={pending}
          onClick={() => onSetQuantity(item, item.quantity + 1)}
          aria-label={`Increase ${item.menu_item_name}`}
        >
          +
        </button>
        <button
          className="danger-icon-button"
          type="button"
          disabled={pending}
          onClick={() => onSetQuantity(item, 0)}
          aria-label={`Remove ${item.menu_item_name}`}
        >
          x
        </button>
      </div>
    </article>
  );
}

function OrderConfirmation({ order }: { order: Order }) {
  return (
    <article className="confirmation-card">
      <p className="section-kicker">Order confirmation</p>
      <h3>
        Order #{order.id} · {order.status}
      </h3>
      <p>
        {formatMoney(order.total_price, order.currency)} · {formatDateTime(order.created_at)}
      </p>
      <ul>
        {order.items.map((item) => (
          <li key={item.id}>
            {item.quantity} x {item.menu_item_name} ·{" "}
            {formatMoney(lineTotal(item.price, item.quantity), item.currency)}
          </li>
        ))}
      </ul>
    </article>
  );
}

function CheckoutForm({
  checkout,
  itemCount,
  total,
  disabled,
}: {
  checkout: CheckoutState;
  itemCount: number;
  /** The cart total, already formatted in the cart's currency. */
  total: string;
  disabled: boolean;
}) {
  const { form, pending, error, updateField, submit } = checkout;
  const inputDisabled = disabled || pending;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void submit();
  };

  return (
    <form className="payment-form" onSubmit={handleSubmit}>
      <div className="panel-heading panel-heading--compact">
        <p className="section-kicker">Demo payment checkout</p>
        <h3>
          {itemCount} item(s), {total}
        </h3>
      </div>
      <Alert tone="danger">{error}</Alert>
      <label>
        Cardholder name
        <input
          value={form.cardholder_name}
          onChange={(event) => updateField("cardholder_name", event.target.value)}
          placeholder="Demo User"
          disabled={inputDisabled}
        />
      </label>
      <label>
        Card number
        <input
          inputMode="numeric"
          value={form.card_number}
          onChange={(event) => updateField("card_number", event.target.value)}
          placeholder="4242 4242 4242 4242"
          disabled={inputDisabled}
        />
      </label>
      <div className="two-fields">
        <label>
          Expiry
          <input
            inputMode="numeric"
            value={form.expiry}
            onChange={(event) => updateField("expiry", event.target.value)}
            placeholder="12/30"
            disabled={inputDisabled}
          />
        </label>
        <label>
          CVV
          <input
            inputMode="numeric"
            value={form.cvv}
            onChange={(event) => updateField("cvv", event.target.value)}
            placeholder="123"
            disabled={inputDisabled}
          />
        </label>
      </div>
      <button className="primary-button" type="submit" disabled={inputDisabled}>
        {pending ? "Processing..." : `Pay ${total}`}
      </button>
    </form>
  );
}

export function CartPanel({
  signedIn,
  cart,
  checkout,
}: {
  signedIn: boolean;
  cart: CartState;
  checkout: CheckoutState;
}) {
  const lines = cart.cart.order_items;
  const total = formatMoney(cart.cart.total_price, cart.cart.currency);

  return (
    <section className="panel-body" aria-label="Cart and payment checkout">
      <div className="panel-heading">
        <p className="section-kicker">Cart review</p>
        <h2>{total} total</h2>
      </div>
      {!signedIn ? (
        <div className="empty-state">Sign in to load and edit your cart.</div>
      ) : (
        <>
          <Alert tone="danger">{cart.error}</Alert>
          {lines.length ? (
            <>
              <div className="cart-lines">
                {lines.map((item) => (
                  <CartLine
                    key={item.order_item_id}
                    item={item}
                    pending={cart.clearing || cart.pendingCartItemId === item.order_item_id}
                    onSetQuantity={cart.setLineQuantity}
                  />
                ))}
              </div>
              <div className="cart-actions">
                <button
                  className="ghost-button"
                  type="button"
                  disabled={cart.clearing || checkout.pending}
                  onClick={cart.removeAll}
                >
                  {cart.clearing ? "Clearing..." : "Clear cart"}
                </button>
              </div>
            </>
          ) : (
            <div className="empty-state">Your cart is empty. Checkout is disabled.</div>
          )}

          {checkout.confirmation ? <OrderConfirmation order={checkout.confirmation} /> : null}

          <CheckoutForm
            checkout={checkout}
            itemCount={cart.itemCount}
            total={total}
            disabled={!lines.length}
          />
        </>
      )}
    </section>
  );
}
