import { Alert } from "./Alert";

const readField = (form, name) => String(form.get(name) || "");

function LoginForm({ pending, onLogin }) {
  const handleSubmit = (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    onLogin({
      username: readField(form, "username").trim(),
      password: readField(form, "password"),
    });
  };

  return (
    <form className="stack-form" onSubmit={handleSubmit}>
      <label>
        Email
        <input name="username" type="email" placeholder="demo@laifood.com" required />
      </label>
      <label>
        Password
        <input name="password" type="password" placeholder="demo123" required />
      </label>
      <div className="demo-credentials">
        <code>demo@laifood.com</code>
        <code>demo123</code>
      </div>
      <button className="primary-button" type="submit" disabled={pending}>
        {pending ? "Signing in..." : "Sign in"}
      </button>
    </form>
  );
}

function SignupForm({ pending, onSignup }) {
  const handleSubmit = async (event) => {
    event.preventDefault();
    // React clears event.currentTarget once the handler returns, so keep the form for the async reset.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const created = await onSignup({
      email: readField(form, "email").trim(),
      password: readField(form, "password"),
      first_name: readField(form, "first_name").trim(),
      last_name: readField(form, "last_name").trim(),
    });

    if (created) {
      formElement.reset();
    }
  };

  return (
    <form className="stack-form" onSubmit={handleSubmit}>
      <label>
        Email
        <input name="email" type="email" required />
      </label>
      <label>
        Password
        <input name="password" type="password" required />
      </label>
      <div className="two-fields">
        <label>
          First name
          <input name="first_name" required />
        </label>
        <label>
          Last name
          <input name="last_name" required />
        </label>
      </div>
      <button className="primary-button" type="submit" disabled={pending}>
        {pending ? "Creating..." : "Create account"}
      </button>
    </form>
  );
}

export function AuthPanel({ session }) {
  const { user, status, mode, pending, message, error, setMode, login, signup, logout } = session;

  return (
    <section className="auth-panel" id="auth-panel">
      <div className="panel-heading">
        <p className="section-kicker">Session gate</p>
        <h2>{user ? "Account restored" : "Login or signup"}</h2>
      </div>

      {status === "loading" ? (
        <div className="loading-row">Checking /me...</div>
      ) : user ? (
        <>
          <Alert tone="success">{message}</Alert>
          <div className="signed-in-card">
            <strong>
              {user.first_name} {user.last_name}
            </strong>
            <span>{user.email}</span>
            <button className="ghost-button" type="button" onClick={logout}>
              Logout
            </button>
          </div>
        </>
      ) : (
        <>
          <div className="auth-tabs" role="tablist" aria-label="Authentication forms">
            <button
              className={mode === "login" ? "active" : ""}
              type="button"
              onClick={() => setMode("login")}
            >
              Login
            </button>
            <button
              className={mode === "signup" ? "active" : ""}
              type="button"
              onClick={() => setMode("signup")}
            >
              Signup
            </button>
          </div>

          <Alert tone="success">{message}</Alert>
          <Alert tone="danger">{error}</Alert>

          {mode === "login" ? (
            <LoginForm pending={pending} onLogin={login} />
          ) : (
            <SignupForm pending={pending} onSignup={signup} />
          )}
        </>
      )}
    </section>
  );
}
