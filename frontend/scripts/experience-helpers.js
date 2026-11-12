export const cacheLayers = [
  { key: "scope", name: "Request scope" },
  { key: "jvm", name: "Caffeine" },
  { key: "redis", name: "Redis" },
  { key: "mysql", name: "MySQL" },
];

export function cacheSummary(samples) {
  const reads = samples.flatMap((sample) => sample.reads || []);
  const events = reads.flatMap((read) => read.events || []);
  return {
    requests: samples.length,
    reads: reads.length,
    scopeHits: events.filter(
      (event) => event.layer === "scope" && event.result === "hit",
    ).length,
    mysqlLoads: events.filter(
      (event) => event.layer === "mysql" && event.result === "load",
    ).length,
  };
}

export function readSource(read) {
  const source = (read.events || []).find(
    (event) => event.result === "hit" || event.result === "load",
  );
  return source
    ? cacheLayers.find((layer) => layer.key === source.layer)?.name ||
        source.layer
    : "Not sampled";
}

export function receiptsComplete(ids, rows, shopId) {
  if (!ids.length || new Set(ids).size !== ids.length) return false;
  return ids.every((id) =>
    rows.some(
      (row) =>
        row.eventId === id &&
        row.state === "PERSISTED" &&
        String(row.shopId) === String(shopId),
    ),
  );
}

export function receiptCount(ids, rows, shopId) {
  return new Set(
    rows
      .filter(
        (row) =>
          ids.includes(row.eventId) &&
          row.state === "PERSISTED" &&
          String(row.shopId) === String(shopId),
      )
      .map((row) => row.eventId),
  ).size;
}

export function sameOrder(first, second) {
  return first != null && second != null && String(first) === String(second);
}

export function orderStatus(order) {
  return (
    {
      1: "Awaiting payment",
      2: "Claimed / awaiting redemption",
      3: "Redeemed",
      4: "Cancelled",
      5: "Refund in progress",
      6: "Refunded",
    }[Number(order?.status)] || "Status unconfirmed"
  );
}
