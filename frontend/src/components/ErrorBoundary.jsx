import { Component } from "react";

/**
 * Keeps a rendering bug in one section from blanking the whole page. The fallback
 * offers a retry (re-render the section) and a full reload.
 */
export class ErrorBoundary extends Component {
  state = { error: null };

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error(`${this.props.label || "UI section"} crashed`, error, info?.componentStack);
  }

  handleRetry = () => {
    this.setState({ error: null });
  };

  render() {
    if (!this.state.error) {
      return this.props.children;
    }

    return (
      <div className="alert alert--danger error-fallback" role="alert">
        <strong>{this.props.label || "This section"} failed to render.</strong>
        <p>Try again, or reload the page if the problem continues.</p>
        <div className="error-fallback__actions">
          <button className="ghost-button" type="button" onClick={this.handleRetry}>
            Try again
          </button>
          <button className="ghost-button" type="button" onClick={() => window.location.reload()}>
            Reload page
          </button>
        </div>
      </div>
    );
  }
}
