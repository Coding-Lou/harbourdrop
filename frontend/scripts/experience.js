import { formatDateTime, money } from "./api.js";
import {
  cacheSummary,
  readSource,
  receiptsComplete,
  receiptCount,
  sameOrder,
  orderStatus,
} from "./experience-helpers.js";

Vue.component("experience-page", {
  props: { api: { type: Function, required: true }, user: Object },
  data() {
    return {
      capabilities: null,
      shops: [],
      shopId: "",
      samples: [],
      cacheBusy: false,
      kafkaBusy: false,
      eventIds: [],
      receipts: [],
      kafkaMessage: "",
      heatBefore: null,
      heatAfter: null,
      vouchers: [],
      voucherId: "",
      voucherLoading: true,
      voucherFailures: 0,
      orderBusy: false,
      confirmOrder: false,
      orderId: "",
      lastVoucher: null,
      requestState: "",
      order: null,
      progress: [],
      duplicate: null,
      orderMessage: "",
      evidence: null,
      error: "",
      loading: true,
    };
  },
  computed: {
    working() {
      return this.cacheBusy || this.kafkaBusy || this.orderBusy;
    },
    cacheResult() {
      return cacheSummary(this.samples);
    },
    received() {
      return receiptCount(this.eventIds, this.receipts, this.shopId);
    },
    selectedVoucher() {
      return this.vouchers.find(
        (voucher) => String(voucher.id) === String(this.voucherId),
      );
    },
    loginUrl() {
      return "/login.html?return=" + encodeURIComponent("/experience.html");
    },
  },
  created() {
    this.disposed = false;
    this.waitTimer = null;
    this.waitResolve = null;
    this.load();
  },
  beforeDestroy() {
    this.disposed = true;
    clearTimeout(this.waitTimer);
    if (this.waitResolve) this.waitResolve();
  },
  methods: {
    money,
    formatDateTime,
    readSource,
    orderStatus,
    layerState(read, key) {
      const events = (read.events || []).filter((event) => event.layer === key);
      if (!events.length) return "Not visited";
      if (events.some((event) => event.result === "load")) return "Loaded";
      return events.some((event) => event.result === "hit") ? "Hit" : "Miss";
    },
    layerClass(read, key) {
      const state = this.layerState(read, key);
      return state === "Hit" ? "hit" : state === "Loaded" ? "source" : "idle";
    },
    capabilityText(enabled, observed, activeText) {
      if (!enabled) return "Disabled";
      if (observed === null || observed === undefined) return "Not reported";
      return observed ? activeText : "Not ready";
    },
    eventTime(value) {
      return value
        ? formatDateTime(value, { seconds: true })
        : "Not yet confirmed";
    },
    async load() {
      this.loading = true;
      this.error = "";
      this.voucherFailures = 0;
      try {
        const [capabilities, catalog] = await Promise.all([
          this.api("/experience/capabilities"),
          this.api("/shop/catalog", { params: { size: 20 } }),
        ]);
        if (this.disposed) return;
        this.capabilities = capabilities.data;
        this.shops = catalog.data || [];
        this.shopId = this.shops.length ? String(this.shops[0].id) : "";
        const vouchers = [];
        for (let index = 0; index < this.shops.length; index += 4) {
          if (this.disposed) return;
          const rows = await Promise.allSettled(
            this.shops.slice(index, index + 4).map(async (shop) => {
              const result = await this.api("/voucher/list/" + shop.id);
              return (result.data || [])
                .filter((voucher) => voucher.orderable === true)
                .map((voucher) => ({ ...voucher, shopName: shop.name }));
            }),
          );
          rows.forEach((row) => {
            if (row.status === "fulfilled") vouchers.push(...row.value);
            else this.voucherFailures++;
          });
        }
        this.vouchers = vouchers.sort(
          (a, b) => Number(a.payValue) - Number(b.payValue),
        );
        this.voucherId = this.vouchers.length
          ? String(this.vouchers[0].id)
          : "";
      } catch (error) {
        if (!this.disposed) this.error = error.message;
      } finally {
        this.loading = false;
        this.voucherLoading = false;
      }
      try {
        const response = await fetch("/assets/experience-evidence.json");
        if (!response.ok) throw new Error("Verification report unavailable");
        const evidence = await response.json();
        if (!this.disposed) this.evidence = evidence;
      } catch {
        /* Live observations remain usable without the historical report. */
      }
    },
    async runCache() {
      if (this.working || !this.shopId) return;
      this.cacheBusy = true;
      this.samples = [];
      this.error = "";
      const id = this.shopId;
      try {
        for (let index = 0; index < 3; index++) {
          const result = await this.api("/experience/cache", {
            params: { shopId: id },
          });
          if (this.disposed) return;
          this.samples.push(result.data);
        }
      } catch (error) {
        if (!this.disposed) this.error = error.message;
      } finally {
        this.cacheBusy = false;
      }
    },
    wait() {
      return new Promise((resolve) => {
        this.waitResolve = resolve;
        this.waitTimer = setTimeout(() => {
          this.waitResolve = null;
          resolve();
        }, 1500);
      });
    },
    async heat() {
      const result = await this.api("/shop/hot-ranking", {
        params: { windowMinutes: 5, limit: 100 },
      });
      return (
        (result.data || []).find(
          (row) => String(row.shop_id) === String(this.shopId),
        ) || { pv: 0, logged_uv: 0, heat: 0 }
      );
    },
    async runKafka() {
      if (this.working || !this.shopId) return;
      this.kafkaBusy = true;
      this.error = "";
      this.receipts = [];
      this.eventIds = [];
      this.heatAfter = null;
      this.kafkaMessage = "Submitting 3 real page views…";
      try {
        this.heatBefore = await this.heat();
        const result = await this.api("/experience/visits", {
          method: "POST",
          params: { shopId: this.shopId },
        });
        this.eventIds = result.data.eventIds;
        for (let attempt = 0; attempt < 12 && !this.disposed; attempt++) {
          const response = await this.api("/experience/visits", {
            params: { eventIds: this.eventIds.join(",") },
          });
          this.receipts = response.data || [];
          if (receiptsComplete(this.eventIds, this.receipts, this.shopId)) {
            this.kafkaMessage = "All 3 events were consumed and persisted.";
            this.heatAfter = await this.heat();
            return;
          }
          this.kafkaMessage = "Submitted. Waiting for Kafka consumer receipts…";
          await this.wait();
        }
        if (!this.disposed)
          this.kafkaMessage =
            "Some events are still unconfirmed. View tracking tolerates loss, so check the receipts again later; the page never resubmits automatically.";
      } catch (error) {
        if (!this.disposed) {
          this.error = error.message;
          this.kafkaMessage = "This run's result is not confirmed yet.";
        }
      } finally {
        this.kafkaBusy = false;
      }
    },
    async refreshReceipts() {
      if (this.working || !this.eventIds.length) return;
      this.kafkaBusy = true;
      try {
        this.receipts =
          (
            await this.api("/experience/visits", {
              params: { eventIds: this.eventIds.join(",") },
            })
          ).data || [];
        this.heatAfter = await this.heat();
        this.kafkaMessage = receiptsComplete(
          this.eventIds,
          this.receipts,
          this.shopId,
        )
          ? "All 3 events were consumed and persisted."
          : "Some events are still pending. Check again later.";
      } catch (error) {
        this.error = error.message;
      } finally {
        this.kafkaBusy = false;
      }
    },
    async submitOrder(repeat = false) {
      if (!this.user || this.working) return;
      const voucher = repeat ? this.lastVoucher : this.selectedVoucher;
      if (!voucher) return;
      this.confirmOrder = false;
      this.orderBusy = true;
      this.error = "";
      try {
        const result = await this.api(
          "/voucher-order/" +
            (voucher.type ? "seckill/" : "ordinary/") +
            voucher.id,
          { method: "POST" },
        );
        const id = String(result.data);
        if (repeat)
          this.duplicate = {
            previous: this.orderId,
            returned: id,
            same: sameOrder(this.orderId, id),
          };
        else {
          this.orderId = id;
          this.lastVoucher = { ...voucher };
          this.order = null;
          this.progress = [];
          this.duplicate = null;
        }
        await this.pollOrder();
      } catch (error) {
        this.error = error.message;
        this.orderMessage =
          !error.status || error.status >= 500
            ? "The result is not confirmed yet. Check My orders first; the page never resubmits automatically."
            : "The request was rejected. See the message above.";
      } finally {
        this.orderBusy = false;
      }
    },
    async pollOrder() {
      for (let attempt = 0; attempt < 12 && !this.disposed; attempt++) {
        if (this.lastVoucher.type) {
          const status = (
            await this.api("/voucher-order/requests/" + this.orderId)
          ).data;
          this.requestState = status.state;
          if (["FAILED", "REVIEW"].includes(status.state)) {
            this.orderMessage =
              status.state === "REVIEW"
                ? "The request was sent for manual review; the purchase slot stays reserved."
                : "The request failed. Check the order status.";
            return;
          }
          if (status.state !== "SUCCESS") {
            this.orderMessage = "Purchase slot queued. Waiting for asynchronous fulfilment…";
            await this.wait();
            continue;
          }
        }
        this.order = (await this.api("/voucher-order/" + this.orderId)).data;
        this.progress =
          (await this.api("/experience/orders/" + this.orderId)).data || [];
        if (
          this.progress.some(
            (event) =>
              event.kind === "ORDER_CREATED" && Number(event.inbox_count) > 0,
          )
        ) {
          this.orderMessage =
            "Order persisted, and its ORDER_CREATED event has an inbox consumer receipt. Payment status is shown below.";
          return;
        }
        this.orderMessage = this.capabilities.mqEnabled
          ? "Order persisted. Waiting for event consumer receipts…"
          : "Order persisted. The business message queue is not enabled.";
        if (!this.capabilities.mqEnabled) return;
        await this.wait();
      }
      if (!this.disposed)
        this.orderMessage = "Order submitted, but not all event receipts are in yet. Refresh later.";
    },
    async refreshOrder() {
      if (this.working || !this.orderId || !this.user || !this.lastVoucher)
        return;
      this.orderBusy = true;
      try {
        await this.pollOrder();
      } catch (error) {
        this.error = error.message;
      } finally {
        this.orderBusy = false;
      }
    },
    async cancelOrder() {
      if (
        this.working ||
        !this.order ||
        !this.lastVoucher ||
        this.lastVoucher.type ||
        Number(this.order.status) !== 1
      )
        return;
      this.orderBusy = true;
      try {
        await this.api("/voucher-order/" + this.orderId + "/cancel", {
          method: "POST",
        });
        this.order = (await this.api("/voucher-order/" + this.orderId)).data;
        this.progress =
          (await this.api("/experience/orders/" + this.orderId)).data || [];
        this.orderMessage = "This unpaid deal order was cancelled.";
        this.lastVoucher = null;
      } catch (error) {
        this.error = error.message;
      } finally {
        this.orderBusy = false;
      }
    },
  },
  template: `
    <div class="experience-page">
      <section class="experience-hero">
        <div><span class="eyebrow">Under the hood · from click to result</span><h1>See what happens behind each request.</h1><p>Pick a shop, press a button, and see what actually happens behind a real request.</p><div class="experience-badges"><span>First two need no sign-in</span><span>Real APIs and receipts</span><span>About a minute to try</span></div></div>
        <div class="experience-hero-mark"><i class="el-icon-data-line" aria-hidden="true"></i><span>TRY · OBSERVE · UNDERSTAND</span></div>
      </section>
      <div v-if="error" class="experience-error" role="alert">{{ error }}<button class="text-button" @click="error = ''">Dismiss</button></div>
      <div v-if="loading" class="experience-loading" role="status">Preparing shops and endpoints…</div>
      <div v-else-if="!capabilities || !capabilities.enabled" class="panel experience-disabled"><h2>The verification page is not enabled</h2><p>It becomes available once the deployer sets HMDP_EXPERIENCE_ENABLED. The rest of the app still works normally.</p><a class="button outline" href="/">Back to home</a></div>
      <template v-else>
        <section class="experience-status" aria-label="Runtime status"><div><span>Cache reads</span><strong>{{ capabilities.cacheEnabled ? 'Enabled' : 'Disabled' }}</strong></div><div><span>Kafka consumer thread</span><strong>{{ capabilityText(capabilities.analyticsEnabled, capabilities.kafkaConsumerRunning, 'Running') }}</strong></div><div><span>Business messaging (Kafka)</span><strong>{{ capabilityText(capabilities.mqEnabled, capabilities.mqClientStarted, 'Started') }}</strong></div><div><span>CDC connector (Debezium)</span><strong>{{ capabilityText(capabilities.cdcEnabled, capabilities.cdcConnected, 'Connected') }}</strong></div></section>
        <div class="experience-picker"><label for="experience-shop">Pick a shop</label><select id="experience-shop" v-model="shopId" :disabled="working" @change="samples = []; eventIds = []; receipts = []; heatBefore = null; heatAfter = null; kafkaMessage = ''"><option v-for="shop in shops" :key="shop.id" :value="String(shop.id)">{{ shop.name }}</option></select><span>Used for the cache and page-view experiments</span></div>
        <section class="panel experience-card" aria-labelledby="cache-experience-title">
          <div class="experience-card-head"><span class="experience-step">01</span><div><span class="eyebrow">Multi-level cache</span><h2 id="cache-experience-title">Same data, fewer hops.</h2><p>Send 3 requests in a row; each one reads the shop twice. See which layer served every read.</p></div><button class="button primary" :disabled="working || !shopId" @click="runCache">{{ cacheBusy ? 'Sampling…' : samples.length ? 'Sample again' : 'Read 3 times' }}<i class="el-icon-right" aria-hidden="true"></i></button></div>
          <div class="experience-layer-guide"><span>Request scope</span><i class="el-icon-arrow-right" aria-hidden="true"></i><span>Caffeine</span><i class="el-icon-arrow-right" aria-hidden="true"></i><span>Redis</span><i class="el-icon-arrow-right" aria-hidden="true"></i><span>MySQL</span></div>
          <div v-if="!samples.length" class="experience-placeholder"><i class="el-icon-view" aria-hidden="true"></i><p>Press the button above to see the actual hit path of each request here.</p></div>
          <template v-else><div class="experience-summary"><div><strong>{{ cacheResult.requests }}</strong><span>HTTP requests</span></div><div><strong>{{ cacheResult.reads }}</strong><span>Shop reads</span></div><div><strong>{{ cacheResult.scopeHits }}</strong><span>Request-scope hits</span></div><div><strong>{{ cacheResult.mysqlLoads }}</strong><span>MySQL loads</span></div></div><div class="experience-table-wrap"><table class="experience-table"><thead><tr><th>Read</th><th>Served by</th><th>Request scope</th><th>Caffeine</th><th>Redis</th><th>MySQL</th><th>Backend time</th></tr></thead><tbody v-for="(sample, index) in samples" :key="sample.requestId"><tr v-for="read in sample.reads" :key="read.sequence"><td>Request {{ index + 1 }} · read {{ read.sequence }}</td><td class="source-name">{{ readSource(read) }}</td><td v-for="key in ['scope','jvm','redis','mysql']" :key="key"><span class="experience-cell" :class="layerClass(read, key)">{{ layerState(read, key) }}</span></td><td>{{ (read.durationMicros / 1000).toFixed(3) }} ms</td></tr></tbody></table></div></template>
          <p class="experience-note">If the second read hits the request-scope cache, the lower layers are skipped. An already-warm cache can make even the first read hit Caffeine; layers that were not visited are never marked as successful. This only samples the current request: it does not flush any cache and is not a load test.</p>
        </section>
        <section class="panel experience-card" aria-labelledby="kafka-experience-title">
          <div class="experience-card-head"><span class="experience-step">02</span><div><span class="eyebrow">Kafka · async analytics</span><h2 id="kafka-experience-title">Every page view gets counted.</h2><p>Emit 3 real page-view events and follow them from submission to consumption and persistence.</p></div><button class="button primary" :disabled="working || !shopId || !capabilities.analyticsEnabled" @click="runKafka">{{ kafkaBusy ? 'Waiting for receipts…' : 'Generate 3 page views' }}<i class="el-icon-right" aria-hidden="true"></i></button></div>
          <div v-if="!eventIds.length" class="experience-placeholder"><i class="el-icon-connection" aria-hidden="true"></i><p>Event receipts will appear here. No sign-in needed.</p></div>
          <template v-else><div class="experience-progress"><span>{{ eventIds.length }} events submitted</span><div role="progressbar" aria-label="Persisted page-view events" :aria-valuenow="received" :aria-valuemax="eventIds.length" aria-valuemin="0"><span :style="{ width: (received / eventIds.length * 100) + '%' }"></span></div><strong>{{ received }} / {{ eventIds.length }} persisted</strong></div><p class="experience-result" role="status">{{ kafkaMessage }}</p><ul class="experience-receipts"><li v-for="id in eventIds" :key="id"><code>{{ id }}</code><span :class="receipts.some(row => row.eventId === id && row.state === 'PERSISTED' && String(row.shopId) === String(shopId)) ? 'confirmed' : 'pending'">{{ receipts.some(row => row.eventId === id && row.state === 'PERSISTED' && String(row.shopId) === String(shopId)) ? 'Consumed and persisted' : 'Awaiting receipt' }}</span></li></ul><div v-if="heatBefore" class="experience-heat"><span>5-minute trending snapshot</span><span>Page views (PV): {{ heatBefore.pv }} → {{ heatAfter ? heatAfter.pv : 'awaiting refresh' }}</span><span>Signed-in UV: {{ heatAfter ? heatAfter.logged_uv : heatBefore.logged_uv }}</span><button class="text-button" :disabled="working" @click="refreshReceipts">Check receipts and trending</button></div></template>
          <p class="experience-note">A successful submit is not a completed consume: an event only shows as done once its ID appears in the database. The trending board refreshes about every 5 seconds, and other visitors affect it too. Anonymous views add to PV; repeat views by the same signed-in user do not add to the window's UV again. This records real page views, and view tracking tolerates loss.</p>
        </section>
        <section class="panel experience-card" aria-labelledby="order-experience-title">
          <div class="experience-card-head"><span class="experience-step">03</span><div><span class="eyebrow">Order idempotency · Kafka · drop fulfilment</span><h2 id="order-experience-title">Click twice, still one order.</h2><p>Create an order with a test account, inspect its event receipts, then deliberately submit it again.</p></div><a v-if="!user" class="button outline" :href="loginUrl">Sign in to try<i class="el-icon-right" aria-hidden="true"></i></a><span v-else class="tag">{{ user.nickName }} · signed in</span></div>
          <div v-if="voucherLoading" class="inline-empty">Looking for available drops and deals…</div><div v-else-if="voucherFailures && !vouchers.length" class="inline-empty">Not every voucher request returned, so we can't tell whether any are available. Refresh the page and try again.</div><div v-else-if="!vouchers.length" class="inline-empty">Nothing is orderable right now. Once the deployer adds a deal, or a drop inside its sale window, you can try this.</div>
          <template v-else><p v-if="voucherFailures" class="experience-note">Some shops' vouchers failed to load; only the ones that loaded are listed below.</p><div class="experience-order-picker"><label for="experience-voucher">Choose a voucher</label><select id="experience-voucher" v-model="voucherId" :disabled="working || !!orderId" @change="confirmOrder = false"><option v-for="voucher in vouchers" :key="voucher.id" :value="String(voucher.id)">{{ voucher.shopName }} · {{ voucher.title }} · \${{ money(voucher.payValue) }} · {{ voucher.type ? 'Drop (flash sale)' : 'Deal' }}</option></select><button v-if="user && !orderId" class="button outline" :disabled="working" @click="confirmOrder = true">Prepare order</button></div>
          <div v-if="confirmOrder && selectedVoucher" class="experience-confirm"><h3>Create this real order?</h3><p>{{ selectedVoucher.title }} · \${{ money(selectedVoucher.payValue) }}. {{ selectedVoucher.type ? 'A successful drop reservation takes stock and your purchase slot; drop orders cannot be cancelled yet.' : Number(selectedVoucher.payValue) === 0 ? 'Free deals go straight to Claimed. Please use a test account.' : 'This only creates an unpaid order; nothing is charged, and you can cancel it afterwards.' }}</p><div><button class="button soft" @click="confirmOrder = false">Not now</button><button class="button primary" :disabled="working" @click="submitOrder(false)">Submit order</button></div></div>
          <template v-if="orderId"><div class="experience-order-result"><span>Order ID</span><code>{{ orderId }}</code><strong>{{ order ? orderStatus(order) : requestState || 'Awaiting confirmation' }}</strong></div><p class="experience-result" role="status">{{ orderMessage }}</p><div class="experience-actions"><button class="button outline small" :disabled="working || !lastVoucher" @click="submitOrder(true)">{{ orderBusy ? 'Working…' : 'Submit again to test idempotency' }}</button><button class="button soft small" :disabled="working || !lastVoucher" @click="refreshOrder">Refresh order and receipts</button><button v-if="order && Number(order.status) === 1 && lastVoucher && !lastVoucher.type" class="text-button" :disabled="working" @click="cancelOrder">Cancel this unpaid deal order</button><a class="text-button" href="/orders.html">My orders<i class="el-icon-right" aria-hidden="true"></i></a></div><div v-if="duplicate" class="experience-duplicate" :class="{ confirmed: duplicate.same }"><strong>{{ duplicate.same ? 'The repeat submit returned the same order ID' : 'The two submits returned different order IDs. Check the result.' }}</strong><span>First {{ duplicate.previous }} → repeat {{ duplicate.returned }}</span></div><div v-if="progress.length" class="experience-table-wrap"><table class="experience-table"><thead><tr><th>Event ID</th><th>Event type</th><th>Outbox state</th><th>Inbox receipts</th><th>Consumed at</th></tr></thead><tbody><tr v-for="event in progress" :key="event.event_id"><td><code>{{ event.event_id }}</code></td><td>{{ event.kind }}</td><td>{{ event.state }}</td><td>{{ event.inbox_count }}</td><td>{{ eventTime(event.consumed_at) }}</td></tr></tbody></table></div></template></template>
          <p class="experience-note">Deals exercise duplicate-order protection and business-event consumption. When you pick a drop inside its sale window, the page also polls its PROCESSING / SUCCESS / REVIEW / FAILED fulfilment state. SUCCESS means the order was persisted, not that it was paid. Orders and events can only be queried by their owner. Times are shown in Vancouver time.</p>
        </section>
      </template>
      <section v-if="evidence" class="experience-evidence"><div><span class="eyebrow">More verification · past evidence</span><h2>Beyond this page: reproducible tests.</h2><p>Failover, CDC changes, compensation and concurrency races need an isolated test environment. This report records the most recent isolated test run and is shown separately from what you just did.</p><span>{{ formatDateTime(evidence.generatedAt) }} · {{ evidence.tests }} passed · {{ evidence.failures }} failed</span></div><details><summary>Coverage and limits<i class="el-icon-arrow-down" aria-hidden="true"></i></summary><ul><li v-for="feature in evidence.features" :key="feature.feature"><strong>{{ feature.feature }}</strong><span>{{ feature.boundary }}</span></li></ul><p><span v-if="evidence.checkstylePassed">Both the functional tests and Checkstyle passed in that run. </span><span v-else>Checkstyle was temporarily skipped in that run. </span>A historical report does not mean the current visitor ran the whole suite. Real payments, refunds and delivery of email sign-in codes are outside what this page verifies.</p><a class="text-button" href="/assets/experience-evidence.json" target="_blank" rel="noopener">View raw report</a></details></section>
    </div>`,
});
