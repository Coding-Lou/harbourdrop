export class ApiError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

// Java Long order IDs must survive parsing without JavaScript precision loss.
export function parseResponseText(text) {
  const lossless = text.replace(
    /"(?:\\.|[^"\\])*"|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
    (token) =>
      /^-?\d{16,}$/.test(token) && !Number.isSafeInteger(Number(token))
        ? JSON.stringify(token)
        : token,
  );
  return JSON.parse(lossless);
}

export function safeReturn(value, origin) {
  try {
    const target = new URL(value || "/", origin);
    return target.origin === origin && !target.pathname.startsWith("/login")
      ? target.pathname + target.search + target.hash
      : "/";
  } catch {
    return "/";
  }
}

export function imagePath(value) {
  const path = String(value || "").trim();
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  if (/^\/(imgs|assets)\//.test(path) && !path.includes("..")) {
    return path;
  }
  if (/^\/blogs\//.test(path) && !path.includes("..")) {
    return "/imgs" + path;
  }
  return "/assets/image-placeholder.svg";
}

export function images(value) {
  return String(value || "")
    .split(",")
    .filter((path) => path.trim())
    .map(imagePath);
}

export function money(cents) {
  if (cents == null || cents === "" || !Number.isFinite(Number(cents))) {
    return "—";
  }
  return (Number(cents) / 100).toFixed(2).replace(/\.00$/, "");
}

export const LOCALE = "en-CA";
export const TIME_ZONE = "America/Vancouver";

const SERVER_TIME =
  /^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2}(?::\d{2})?)(?:\.(\d+))?)?(Z|[+-]\d{2}(?::?\d{2})?)?$/i;

// The backend sends LocalDateTime values in UTC without an offset, e.g.
// "2026-11-12T20:30:00" or "2026-11-12T20:30:00.123456". Treat them as UTC
// rather than letting the browser read them as local time. Returns epoch ms or NaN.
export function parseServerTime(value) {
  if (value == null || value === "") {
    return NaN;
  }
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : NaN;
  }
  if (Array.isArray(value)) {
    // Jackson's array form: [year, month, day, hour, minute, second, nanos]
    const [year, month = 1, day = 1, hour = 0, minute = 0, second = 0, nanos = 0] =
      value.map(Number);
    const time = Date.UTC(year, month - 1, day, hour, minute, second, Math.floor(nanos / 1e6));
    return Number.isFinite(time) ? time : NaN;
  }
  const match = SERVER_TIME.exec(String(value).trim());
  if (!match) {
    return NaN;
  }
  const [, day, clock = "00:00:00", fraction = "", offset = "Z"] = match;
  const millis = fraction ? "." + fraction.slice(0, 3).padEnd(3, "0") : "";
  let zone = offset.toUpperCase();
  if (zone !== "Z") {
    const digits = zone.replace(":", "");
    zone = digits.slice(0, 3) + ":" + (digits.slice(3) || "00");
  }
  return Date.parse(day + "T" + clock + millis + zone);
}

const formatters = new Map();
function formatter(options) {
  const key = JSON.stringify(options);
  if (!formatters.has(key)) {
    formatters.set(
      key,
      new Intl.DateTimeFormat(LOCALE, { timeZone: TIME_ZONE, ...options }),
    );
  }
  return formatters.get(key);
}

// ICU renders en-CA day periods as "a.m."/"p.m." and may use narrow no-break
// spaces; normalise so the UI reads "8:30 PM" in every browser.
function tidy(text) {
  return text
    .replace(/\s+/g, " ")
    .replace(/a\.\s?m\./gi, "AM")
    .replace(/p\.\s?m\./gi, "PM");
}

function toTime(value) {
  return typeof value === "number" ? value : parseServerTime(value);
}

function timeParts(ms) {
  const parts = formatter({ hour: "numeric", minute: "2-digit" }).formatToParts(ms);
  const clock = parts
    .filter((part) => part.type !== "dayPeriod")
    .map((part) => part.value)
    .join("")
    .trim();
  const period = tidy(
    (parts.find((part) => part.type === "dayPeriod") || { value: "" }).value,
  );
  return { clock, period };
}

// Vancouver calendar day as "YYYY-MM-DD".
function dayKey(ms) {
  return formatter({ year: "numeric", month: "2-digit", day: "2-digit" }).format(ms);
}

function shortDate(ms) {
  return tidy(formatter({ month: "short", day: "numeric" }).format(ms));
}

function dayLabel(ms, now) {
  const day = dayKey(ms);
  const today = dayKey(now);
  if (day === today) {
    return "";
  }
  const [year, month, date] = today.split("-").map(Number);
  const tomorrow = new Date(Date.UTC(year, month - 1, date + 1))
    .toISOString()
    .slice(0, 10);
  return day === tomorrow ? "Tomorrow" : shortDate(ms);
}

// "8:30 PM" in Vancouver time.
export function formatTime(value) {
  const ms = toTime(value);
  if (!Number.isFinite(ms)) {
    return "—";
  }
  const { clock, period } = timeParts(ms);
  return period ? clock + " " + period : clock;
}

// "Nov 12, 2026" in Vancouver time.
export function formatDate(value) {
  const ms = toTime(value);
  return Number.isFinite(ms)
    ? tidy(formatter({ year: "numeric", month: "short", day: "numeric" }).format(ms))
    : "—";
}

// "Nov 12, 2026, 8:30 PM" (or with seconds) in Vancouver time.
export function formatDateTime(value, { seconds = false } = {}) {
  const ms = toTime(value);
  if (!Number.isFinite(ms)) {
    return "—";
  }
  const time = seconds
    ? tidy(
        formatter({ hour: "numeric", minute: "2-digit", second: "2-digit" }).format(ms),
      )
    : formatTime(ms);
  return formatDate(ms) + ", " + time;
}

// "8:30 PM" today, "Tomorrow, 8:30 PM", otherwise "Nov 14, 8:30 PM".
export function formatMoment(value, now = Date.now()) {
  const ms = toTime(value);
  if (!Number.isFinite(ms)) {
    return "—";
  }
  const day = dayLabel(ms, now);
  return (day ? day + ", " : "") + formatTime(ms);
}

// Pickup window: "8:30–9:00 PM", "11:30 AM–1:00 PM", "Tomorrow, 8:30–9:00 PM",
// or "Nov 12, 8:30 PM–Nov 13, 9:00 AM" when the window crosses midnight.
// Returns "" when either end is missing.
export function formatWindow(begin, end, now = Date.now()) {
  const start = toTime(begin);
  const finish = toTime(end);
  if (!Number.isFinite(start) || !Number.isFinite(finish)) {
    return "";
  }
  if (dayKey(start) !== dayKey(finish)) {
    return (
      shortDate(start) + ", " + formatTime(start) + "–" +
      shortDate(finish) + ", " + formatTime(finish)
    );
  }
  const from = timeParts(start);
  const to = timeParts(finish);
  const range =
    from.period === to.period
      ? from.clock + "–" + to.clock + " " + to.period
      : formatTime(start) + "–" + formatTime(finish);
  const day = dayLabel(start, now);
  return (day ? day + ", " : "") + range.trim();
}

export function createApi({
  fetchImpl = fetch,
  tokenProvider,
  onUnauthorized,
  refreshSession,
  timeout = 10000,
}) {
  // One shared refresh for concurrent 401s; refresh tokens are single-use.
  let refreshing = null;
  function renew() {
    if (!refreshSession) {
      return Promise.resolve(false);
    }
    refreshing ||= Promise.resolve()
      .then(refreshSession)
      .catch(() => false)
      .finally(() => {
        refreshing = null;
      });
    return refreshing;
  }
  return async function request(
    path,
    { method = "GET", params, body, quiet = false } = {},
    retried = false,
  ) {
    const query = new URLSearchParams();
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value != null && value !== "") {
        query.set(key, String(value));
      }
    });
    const url = "/api" + path + (query.size ? "?" + query.toString() : "");
    const headers = { Accept: "application/json" };
    const token = tokenProvider();
    if (token) {
      headers.Authorization = "Bearer " + token;
    }
    const multipart =
      typeof FormData !== "undefined" && body instanceof FormData;
    if (body != null && !multipart) {
      headers["Content-Type"] = "application/json";
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const response = await fetchImpl(url, {
        method,
        headers,
        credentials: "same-origin",
        signal: controller.signal,
        body:
          body == null ? undefined : multipart ? body : JSON.stringify(body),
      });
      if (response.status === 401) {
        // An expired access token is renewed once with the refresh token, then retried.
        if (token && !retried && (await renew())) {
          return request(path, { method, params, body, quiet }, true);
        }
        onUnauthorized(quiet);
        throw new ApiError("Your session has expired. Please sign in again.", 401);
      }
      if (!response.ok) {
        throw new ApiError(
          response.status === 429
            ? "Too many requests. Please wait a moment and try again."
            : "The service is temporarily unavailable. Please try again shortly.",
          response.status,
        );
      }
      const result = parseResponseText(await response.text());
      if (!result.success) {
        throw new ApiError(
          result.errorMsg || "That didn't go through. Please try again.",
          response.status,
        );
      }
      return result;
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw new ApiError(
        error.name === "AbortError"
          ? "The request timed out. Please try again shortly."
          : "Network error. Please check your connection.",
      );
    } finally {
      clearTimeout(timer);
    }
  };
}
