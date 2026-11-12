import test from "node:test";
import assert from "node:assert/strict";
import {
  ApiError,
  createApi,
  parseResponseText,
  safeReturn,
  imagePath,
  money,
  parseServerTime,
  formatTime,
  formatDate,
  formatDateTime,
  formatMoment,
  formatWindow,
} from "../scripts/api.js";

test("Java Long IDs retain every digit while normal numbers and string contents stay unchanged", () => {
  const result = parseResponseText(
    '{"success":true,"data":{"order_id":339587467398192145,"stock":9,"text":"Order 339587467398192145, \\"price\\"","price":0.12345678901234567,"scientific":1.2e3,"negative":-9223372036854775808}}',
  );
  assert.equal(result.data.order_id, "339587467398192145");
  assert.equal(result.data.negative, "-9223372036854775808");
  assert.equal(result.data.stock, 9);
  assert.equal(result.data.text, 'Order 339587467398192145, "price"');
  assert.equal(result.data.price, 0.12345678901234567);
  assert.equal(result.data.scientific, 1200);
});

test("login returns only to the current origin and never loops back into login", () => {
  const origin = "http://127.0.0.1:8080";
  assert.equal(
    safeReturn("/shop-detail.html?id=4", origin),
    "/shop-detail.html?id=4",
  );
  assert.equal(safeReturn("//example.com/path", origin), "/");
  assert.equal(safeReturn("https://example.com/path", origin), "/");
  assert.equal(safeReturn("javascript:alert(1)", origin), "/");
  assert.equal(safeReturn("/login.html?return=/login.html", origin), "/");
});

test("uploaded paths resolve to original image storage and unsafe schemes use the placeholder", () => {
  assert.equal(
    imagePath("/blogs/1/2/example.png"),
    "/imgs/blogs/1/2/example.png",
  );
  assert.equal(imagePath("/imgs/blogs/example.png"), "/imgs/blogs/example.png");
  assert.equal(
    imagePath("javascript:alert(1)"),
    "/assets/image-placeholder.svg",
  );
  assert.equal(
    imagePath("/imgs/../scripts/app.js"),
    "/assets/image-placeholder.svg",
  );
});

test("voucher cents render correctly including free vouchers and missing amounts", () => {
  assert.equal(money(4750), "47.50");
  assert.equal(money(5000), "50");
  assert.equal(money(0), "0");
  assert.equal(money(null), "—");
});

test("requests encode searches with special characters and retain zero/false values and fresh authorization", async () => {
  let token = "first-token";
  const requests = [];
  const request = createApi({
    tokenProvider: () => token,
    onUnauthorized() {},
    fetchImpl: async (url, options) => {
      requests.push({ url, options });
      return new Response('{"success":true,"data":[]}');
    },
  });
  await request("/shop/catalog", {
    params: { keyword: "Dim sum & coffee", page: 0, liked: false },
  });
  token = "second-token";
  await request("/user/me");
  const url = new URL(requests[0].url, "http://127.0.0.1");
  assert.equal(url.searchParams.get("keyword"), "Dim sum & coffee");
  assert.equal(url.searchParams.get("page"), "0");
  assert.equal(url.searchParams.get("liked"), "false");
  assert.equal(requests[0].options.headers.Authorization, "Bearer first-token");
  assert.equal(requests[1].options.headers.Authorization, "Bearer second-token");
});

test("an expired access token is refreshed once and the request retried", async () => {
  let token = "expired";
  let refreshes = 0;
  const seen = [];
  const request = createApi({
    tokenProvider: () => token,
    onUnauthorized() {
      assert.fail("a successful refresh must not log the user out");
    },
    refreshSession: async () => {
      refreshes += 1;
      token = "renewed";
      return true;
    },
    fetchImpl: async (url, options) => {
      seen.push(options.headers.Authorization);
      return options.headers.Authorization === "Bearer renewed"
        ? new Response('{"success":true,"data":1}')
        : new Response("", { status: 401 });
    },
  });
  // Two concurrent 401s share one refresh, because refresh tokens are single-use.
  const [first, second] = await Promise.all([
    request("/user/me"),
    request("/user/me"),
  ]);
  assert.equal(first.data, 1);
  assert.equal(second.data, 1);
  assert.equal(refreshes, 1);
  assert.deepEqual(seen.slice(0, 2), ["Bearer expired", "Bearer expired"]);
});

test("a failed refresh falls back to logging out", async () => {
  let loggedOut = false;
  const request = createApi({
    tokenProvider: () => "expired",
    onUnauthorized() {
      loggedOut = true;
    },
    refreshSession: async () => false,
    fetchImpl: async () => new Response("", { status: 401 }),
  });
  await assert.rejects(request("/user/me"), (error) => error.status === 401);
  assert.equal(loggedOut, true);
});

test("business rejection retains its actual message and differs from an uncertain network failure", async () => {
  const request = createApi({
    tokenProvider: () => null,
    onUnauthorized() {},
    fetchImpl: async () =>
      new Response('{"success":false,"errorMsg":"Out of stock."}'),
  });
  await assert.rejects(
    request("/voucher-order/ordinary/1", { method: "POST" }),
    (error) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.message, "Out of stock.");
      assert.equal(error.status, 200);
      return true;
    },
  );
});

test("401 clears login through the callback and retains quiet/redirect intent", async () => {
  let quietValue;
  const request = createApi({
    tokenProvider: () => "expired",
    onUnauthorized: (quiet) => {
      quietValue = quiet;
    },
    fetchImpl: async () => new Response("", { status: 401 }),
  });
  await assert.rejects(
    request("/user/me", { quiet: true }),
    (error) => error.status === 401,
  );
  assert.equal(quietValue, true);
});

test("timeout and throttling show actionable messages rather than reporting success", async () => {
  const timeoutRequest = createApi({
    tokenProvider: () => null,
    onUnauthorized() {},
    timeout: 5,
    fetchImpl: (_, options) =>
      new Promise((resolve, reject) => {
        options.signal.addEventListener("abort", () =>
          reject(new DOMException("Aborted", "AbortError")),
        );
      }),
  });
  await assert.rejects(timeoutRequest("/shop/catalog"), /timed out/);
  const limitedRequest = createApi({
    tokenProvider: () => null,
    onUnauthorized() {},
    fetchImpl: async () => new Response("", { status: 429 }),
  });
  await assert.rejects(limitedRequest("/user/code"), /Too many requests/);
});

test("server LocalDateTime strings without an offset are read as UTC", () => {
  const expected = Date.UTC(2026, 10, 12, 20, 30, 0);
  assert.equal(parseServerTime("2026-11-12T20:30:00"), expected);
  assert.equal(parseServerTime("2026-11-12 20:30:00"), expected);
  assert.equal(parseServerTime("2026-11-12T20:30"), expected);
  assert.equal(parseServerTime("2026-11-12T20:30:00.123456"), expected + 123);
  assert.equal(parseServerTime("2026-11-12T20:30:00.5"), expected + 500);
  // Explicit offsets are honoured rather than overridden.
  assert.equal(parseServerTime("2026-11-12T20:30:00Z"), expected);
  assert.equal(parseServerTime("2026-11-12T12:30:00-08:00"), expected);
  assert.equal(parseServerTime("2026-11-12T20:30:00.000+0000"), expected);
  assert.equal(parseServerTime([2026, 11, 12, 20, 30]), expected);
  assert.equal(parseServerTime(expected), expected);
  assert.ok(Number.isNaN(parseServerTime(null)));
  assert.ok(Number.isNaN(parseServerTime("")));
  assert.ok(Number.isNaN(parseServerTime("not a time")));
});

test("times render in Vancouver, including across daylight saving time", () => {
  // January 2026 is PST (UTC-8) in every tzdata version (newer releases keep BC on PDT
  // year-round from late 2026), so winter expectations use January: 04:30 UTC on Jan 13 is
  // 8:30 PM on Jan 12.
  assert.equal(formatTime("2026-01-13T04:30:00"), "8:30 PM");
  assert.equal(formatDate("2026-01-13T04:30:00"), "Jan 12, 2026");
  // July is PDT (UTC-7): 03:30 UTC on Jul 1 is 8:30 PM on Jun 30.
  assert.equal(formatTime("2026-07-01T03:30:00"), "8:30 PM");
  assert.equal(formatDate("2026-07-01T03:30:00"), "Jun 30, 2026");
  // Spring-forward day: 09:59 UTC is 1:59 AM PST, 10:00 UTC is 3:00 AM PDT.
  assert.equal(formatTime("2026-03-08T09:59:00"), "1:59 AM");
  assert.equal(formatTime("2026-03-08T10:00:00"), "3:00 AM");
  assert.equal(
    formatDateTime("2026-10-10T15:13:58.466556+00:00", { seconds: true }),
    "Oct 10, 2026, 8:13:58 AM",
  );
  assert.equal(formatTime(null), "—");
  assert.equal(formatDate("garbage"), "—");
});

test("pickup windows collapse shared day periods and show dates when needed", () => {
  const now = parseServerTime("2026-01-12T20:00:00"); // Jan 12, 12:00 PM in Vancouver
  assert.equal(
    formatWindow("2026-01-13T04:30:00", "2026-01-13T05:00:00", now),
    "8:30–9:00 PM",
  );
  assert.equal(
    formatWindow("2026-01-12T19:30:00", "2026-01-12T21:00:00", now),
    "11:30 AM–1:00 PM",
  );
  assert.equal(
    formatWindow("2026-01-13T04:30:00", "2026-01-13T17:00:00", now),
    "Jan 12, 8:30 PM–Jan 13, 9:00 AM",
  );
  assert.equal(
    formatWindow("2026-01-14T04:30:00", "2026-01-14T05:00:00", now),
    "Tomorrow, 8:30–9:00 PM",
  );
  assert.equal(
    formatWindow("2026-01-16T04:30:00", "2026-01-16T05:00:00", now),
    "Jan 15, 8:30–9:00 PM",
  );
  assert.equal(formatWindow(null, "2026-01-13T05:00:00", now), "");
  assert.equal(formatWindow(undefined, undefined, now), "");
  assert.equal(formatMoment("2026-01-13T02:00:00", now), "6:00 PM");
  assert.equal(formatMoment("2026-01-14T02:00:00", now), "Tomorrow, 6:00 PM");
});
