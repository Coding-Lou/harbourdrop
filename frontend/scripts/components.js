import {
  formatMoment,
  formatWindow,
  imagePath,
  images,
  money,
  parseServerTime,
} from "./api.js";

Vue.component("app-image", {
  props: ["src", "alt", "eager"],
  data() {
    return { failed: false };
  },
  watch: {
    src() {
      this.failed = false;
    },
  },
  computed: {
    source() {
      return this.failed
        ? "/assets/image-placeholder.svg"
        : imagePath(this.src);
    },
  },
  template:
    '<img :src="source" :alt="alt || \'\'" :loading="eager ? \'eager\' : \'lazy\'" decoding="async" referrerpolicy="no-referrer" @error="failed = true">',
});

Vue.component("blog-card", {
  props: ["blog"],
  methods: { images },
  template: `
    <article class="blog-card">
      <a :href="'/blog-detail.html?id=' + blog.id" class="blog-cover" :aria-label="blog.title">
        <app-image :src="images(blog.images)[0]" :alt="blog.title"></app-image>
      </a>
      <div class="blog-card-body">
        <a :href="'/blog-detail.html?id=' + blog.id" class="blog-card-title" :title="blog.title">{{ blog.title }}</a>
        <div class="blog-card-footer">
          <a :href="'/other-info.html?id=' + blog.userId" class="author">
            <app-image :src="blog.icon || '/assets/avatar.svg'" alt=""></app-image>
            <span>{{ blog.name || 'Local foodie' }}</span>
          </a>
          <button class="like-button" :class="{ liked: blog.isLike }" :disabled="blog.busy" :aria-pressed="!!blog.isLike" :aria-label="(blog.isLike ? 'Unlike: ' : 'Like: ') + blog.title" @click="$emit('like', blog)">
            <i class="el-icon-star-off" aria-hidden="true"></i><span>{{ blog.liked || 0 }}</span>
          </button>
        </div>
      </div>
    </article>`,
});

Vue.component("rating-stars", {
  props: ["score"],
  computed: {
    fill() {
      return Math.max(0, Math.min(100, Number(this.score || 0) * 2)) + "%";
    },
  },
  template:
    '<span class="rating-stars" aria-hidden="true">★★★★★<span :style="{ width: fill }">★★★★★</span></span>',
});

Vue.component("shop-card", {
  props: ["shop", "typeName"],
  methods: { images },
  template: `
    <a class="shop-card" :href="'/shop-detail.html?id=' + shop.id">
      <div class="shop-cover"><app-image :src="images(shop.images)[0]" :alt="shop.name"></app-image><span v-if="typeName" class="image-tag">{{ typeName }}</span></div>
      <div class="shop-card-body"><h3>{{ shop.name }}</h3>
        <div class="shop-score"><rating-stars :score="shop.score"></rating-stars><strong>{{ shop.score == null ? 'No rating yet' : (shop.score / 10).toFixed(1) }}</strong><span class="muted">{{ shop.comments || 0 }} {{ Number(shop.comments) === 1 ? 'review' : 'reviews' }}</span></div>
        <p class="muted shop-address"><i class="el-icon-location-outline" aria-hidden="true"></i>{{ shop.area || shop.address }}</p>
        <div class="shop-card-bottom"><span v-if="shop.avgPrice == null" class="muted">Price not listed</span><span v-else>~<strong>\${{ shop.avgPrice }}</strong> per person</span><span class="muted">View shop <i class="el-icon-right" aria-hidden="true"></i></span></div>
      </div>
    </a>`,
});

Vue.component("voucher-card", {
  props: ["voucher", "shopName", "busy"],
  methods: { money },
  computed: {
    available() {
      const v = this.voucher;
      if (v.orderable === false || (v.status != null && v.status !== 1)) {
        return false;
      }
      return (
        !v.type ||
        (Number(v.stock) > 0 &&
          Date.now() >= parseServerTime(v.beginTime) &&
          Date.now() <= parseServerTime(v.endTime))
      );
    },
    // Sale-window state of a drop (type 1): upcoming, open, sold out or ended.
    saleState() {
      const v = this.voucher;
      const now = Date.now();
      const begin = parseServerTime(v.beginTime);
      const end = parseServerTime(v.endTime);
      if (Number.isFinite(end) && now > end) {
        return { kind: "ended", text: "Ended" };
      }
      if (!(Number(v.stock) > 0)) {
        return { kind: "sold-out", text: "Sold out" };
      }
      if (Number.isFinite(begin) && now < begin) {
        return { kind: "upcoming", text: "Opens " + formatMoment(begin, now) };
      }
      return { kind: "open", text: Number(v.stock) + " left" };
    },
    pickup() {
      return formatWindow(
        this.voucher.pickupBeginTime,
        this.voucher.pickupEndTime,
      );
    },
    label() {
      if (this.busy) {
        return "Submitting…";
      }
      if (!this.available) {
        if (this.voucher.type) {
          return {
            "sold-out": "Sold out",
            ended: "Drop ended",
            upcoming: "Not open yet",
          }[this.saleState.kind] || "Unavailable";
        }
        return "Unavailable";
      }
      return this.voucher.type
        ? "Reserve a bag"
        : Number(this.voucher.payValue) === 0
          ? "Claim deal"
          : "Buy deal";
    },
  },
  template: `
    <article class="voucher-card" :class="{ 'drop-card': voucher.type }">
      <div class="ticket-mark"><img v-if="voucher.type" class="bag-mark" src="/assets/shops/bag.svg" alt=""><i v-else class="el-icon-tickets" aria-hidden="true"></i></div>
      <div class="voucher-main"><a v-if="shopName" :href="'/shop-detail.html?id=' + voucher.shopId" class="eyebrow">{{ shopName }}</a><span class="voucher-kind">{{ voucher.type ? 'Surprise bag' : 'Deal' }}</span><h3>{{ voucher.title }}</h3><p>{{ voucher.subTitle || 'See the shop for terms of use.' }}</p><div class="voucher-price">\$<strong>{{ money(voucher.payValue) }}</strong><span>{{ voucher.type ? 'Worth' : 'Value' }} \${{ money(voucher.actualValue) }}</span><span v-if="voucher.type" class="tag" :class="'sale-' + saleState.kind">{{ saleState.text }}</span></div><p v-if="voucher.type && pickup" class="voucher-pickup"><i class="el-icon-time" aria-hidden="true"></i>Pick up {{ pickup }}</p></div>
      <button class="button primary" :disabled="!available || busy" @click="$emit('claim', voucher)">{{ label }}</button>
    </article>`,
});
