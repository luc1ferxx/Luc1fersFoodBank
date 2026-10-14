const { findInlineScripts } = require("../scripts/verify-csp-build");

test("flags the inline webpack runtime that the backend CSP blocks", () => {
  const html =
    '<html><head><script defer="defer" src="/static/js/main.js"></script></head>' +
    '<body><div id="root"></div><script>!function(e){function r(r){}}([])</script></body></html>';

  expect(findInlineScripts(html)).toEqual(["!function(e){function r(r){}}([])"]);
});

test("accepts a build that only loads external scripts", () => {
  const html =
    '<html><body><div id="root"></div>' +
    '<script src="/static/js/runtime-main.js"></script>' +
    '<script src="/static/js/2.chunk.js"></script>' +
    '<script src="/static/js/main.chunk.js"></script></body></html>';

  expect(findInlineScripts(html)).toEqual([]);
});
