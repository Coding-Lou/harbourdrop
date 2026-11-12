import {
  createApi,
  formatDate,
  formatWindow,
  images,
  imagePath,
  money,
  safeReturn,
} from "./api.js";
import "./components.js";
import "./experience.js";

const query = new URLSearchParams(location.search);
const routes = {
  "/": "home",
  "/index.html": "home",
  "/shop-list.html": "shops",
  "/shop-detail.html": "shop",
  "/blog-detail.html": "blog",
  "/blog-edit.html": "publish",
  "/login.html": "login",
  "/login2.html": "login",
  "/info.html": "profile",
  "/other-info.html": "profile",
  "/orders.html": "orders",
  "/vouchers.html": "vouchers",
  "/experience.html": "experience",
};
const page = routes[location.pathname] || "not-found";
const EMAIL_PATTERN =
  /^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/;

function storeSession(tokens) {
  sessionStorage.setItem("token", tokens.accessToken);
  sessionStorage.setItem("refreshToken", tokens.refreshToken);
}

function clearSession() {
  sessionStorage.removeItem("token");
  sessionStorage.removeItem("refreshToken");
}

let app;
const api = createApi({
  tokenProvider: () => sessionStorage.getItem("token"),
  refreshSession: async () => {
    const refreshToken = sessionStorage.getItem("refreshToken");
    if (!refreshToken) {
      return false;
    }
    const response = await fetch("/api/user/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({ refreshToken }),
    });
    const result = response.ok ? await response.json() : null;
    if (!result || !result.success) {
      return false;
    }
    storeSession(result.data);
    return true;
  },
  onUnauthorized: (quiet) => {
    clearSession();
    if (app) {
      app.user = null;
    }
    if (!quiet) {
      location.assign(
        "/login.html?return=" +
          encodeURIComponent(location.pathname + location.search),
      );
    }
  },
});

app = new Vue({
  el: "#app",
  data: {
    page,
    user: null,
    userReady: false,
    types: [],
    loading: true,
    pageError: "",
    keyword: query.get("keyword") || "",
    toast: null,
    dialog: null,
    blogs: [],
    blogPage: 1,
    blogLoading: false,
    blogHasMore: true,
    feed: query.get("feed") || "hot",
    feedLastId: Date.now(),
    feedOffset: 0,
    homeShops: [],
    homeOffer: null,
    asideError: "",
    shops: [],
    catalogPage: 1,
    catalogTotal: 0,
    catalogLoading: false,
    catalogSequence: 0,
    catalogType: query.get("type") || "",
    catalogSort: query.get("sort") || "score",
    shop: null,
    vouchers: [],
    shopBlogs: [],
    blog: null,
    blogShop: null,
    following: false,
    followingBusy: false,
    galleryIndex: 0,
    claimBusy: "",
    orderError: "",
    offerShopsPage: 1,
    offerShopsTotal: 0,
    offers: [],
    offerLoading: false,
    offerHasMore: true,
    profileUser: null,
    signCount: 0,
    signBusy: false,
    signed: false,
    orders: [],
    orderPage: 1,
    orderTotal: 0,
    orderLoading: false,
    pendingRequest: null,
    requestMessage: "",
    email: "",
    code: "",
    loginBusy: false,
    codeBusy: false,
    codeCountdown: 0,
    loginError: "",
    publishShops: [],
    shopQuery: "",
    publishShopLoading: false,
    uploadBusy: false,
    publishBusy: false,
    draft: {
      title: "",
      content: "",
      shopId: query.get("shop") || "",
      pictures: [],
    },
    draftSaved: false,
  },
  computed: {
    activeNav() {
      if (this.page === "experience") return "experience";
      if (this.page === "vouchers") {
        return "vouchers";
      }
      if (["shops", "shop"].includes(this.page)) {
        return "shops";
      }
      return "home";
    },
    currentType() {
      return this.types.find(
        (type) => String(type.id) === String(this.catalogType),
      );
    },
    isOwnProfile() {
      return (
        !query.get("id") ||
        (this.user && String(query.get("id")) === String(this.user.id))
      );
    },
    shopPictures() {
      return images(this.shop && this.shop.images);
    },
    blogPictures() {
      return images(this.blog && this.blog.images);
    },
    catalogHasMore() {
      return this.shops.length < this.catalogTotal;
    },
    profileName() {
      return this.profileUser ? this.profileUser.nickName : "Local foodie";
    },
  },
  created() {
    this.toastTimer = null;
    this.codeTimer = null;
    this.requestTimer = null;
    this.boot();
  },
  beforeDestroy() {
    clearTimeout(this.toastTimer);
    clearInterval(this.codeTimer);
    clearTimeout(this.requestTimer);
  },
  watch: {
    dialog(value) {
      this.$nextTick(() => {
        if (value) {
          this.dialogOrigin = document.activeElement;
          document.body.style.overflow = "hidden";
          const button =
            this.$refs.dialogPanel &&
            this.$refs.dialogPanel.querySelector("button");
          if (button) {
            button.focus();
          }
        } else {
          document.body.style.overflow = "";
          if (this.dialogOrigin) {
            this.dialogOrigin.focus();
          }
        }
      });
    },
  },
  methods: {
    experienceApi(path, options) {
      return api(path, options);
    },
    images,
    imagePath,
    money,
    formatWindow,
    typeName(id) {
      const type = this.types.find((item) => String(item.id) === String(id));
      return type ? type.name : "Local favourites";
    },
    typeIcon(id) {
      return (
        {
          1: "dessert", // Bakeries
          2: "coffee-cup", // Cafés
          3: "dish", // Sushi & Japanese
          4: "food", // Dim Sum & Chinese
          5: "knife-fork", // Pizza & Italian
          6: "sunrise", // Brunch
          7: "grape", // Plant-based
          8: "shopping-bag-1", // Grocery & Deli
        }[id] || "discover"
      );
    },
    date(value) {
      return formatDate(value);
    },
    orderStatus(status) {
      return (
        {
          1: "Awaiting payment",
          2: "Claimed",
          3: "Redeemed",
          4: "Cancelled",
          5: "Refund in progress",
          6: "Refunded",
        }[status] || "Processing"
      );
    },
    notify(message, kind = "success") {
      this.toast = { message, kind };
      clearTimeout(this.toastTimer);
      this.toastTimer = setTimeout(() => {
        this.toast = null;
      }, 4500);
    },
    showError(error) {
      if (error.status !== 401) {
        this.notify(error.message || "Something went wrong. Please try again.", "error");
      }
    },
    reload() {
      location.reload();
    },
    trapDialog(event) {
      const controls = this.$refs.dialogPanel.querySelectorAll(
        "button:not([disabled]), a[href], input, select, textarea",
      );
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      }
      if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    },
    requireUser() {
      if (this.user) {
        return true;
      }
      location.assign(
        "/login.html?return=" +
          encodeURIComponent(location.pathname + location.search),
      );
      return false;
    },
    async boot() {
      if (sessionStorage.getItem("token")) {
        try {
          this.user = (await api("/user/me", { quiet: true })).data;
        } catch (error) {
          if (error.status !== 401) {
            this.showError(error);
          }
        }
      }
      this.userReady = true;
      if (["orders", "publish", "blog", "profile"].includes(this.page)) {
        if (!this.requireUser()) {
          return;
        }
      }
      try {
        this.types = (await api("/shop-type/list")).data || [];
      } catch (error) {
        this.showError(error);
      }
      try {
        if (this.page === "home") {
          await this.loadHome();
        }
        if (this.page === "shops") {
          await this.loadCatalog(true);
        }
        if (this.page === "shop") {
          await this.loadShop();
        }
        if (this.page === "blog") {
          await this.loadBlog();
        }
        if (this.page === "vouchers") {
          await this.loadOffers(true);
        }
        if (this.page === "profile") {
          await this.loadProfile();
        }
        if (this.page === "orders") {
          await this.loadOrders();
          this.watchRequest();
        }
        if (this.page === "publish") {
          await this.loadPublishShops();
        }
      } catch (error) {
        if (error.status !== 401) {
          this.pageError = error.message;
        }
      } finally {
        this.loading = false;
      }
      document.title =
        (this.shop
          ? this.shop.name
          : this.blog
            ? this.blog.title
            : {
                shops: "Local spots",
                vouchers: "Drops & deals",
                profile: this.profileName,
                orders: "My orders",
                publish: "Write a review",
                login: "Sign in",
                experience: "Under the hood",
              }[this.page] || "Surplus food drops near you") + " · HarbourDrop";
    },
    search() {
      location.assign(
        "/shop-list.html?" +
          new URLSearchParams({ keyword: this.keyword.trim() }),
      );
    },
    async loadHome() {
      const results = await Promise.allSettled([
        this.loadBlogs(true),
        api("/shop/catalog", { params: { page: 1, size: 3, sort: "score" } }),
      ]);
      if (results[1].status === "fulfilled") {
        this.homeShops = results[1].value.data || [];
      } else {
        this.asideError = results[1].reason.message;
      }
      const ids = [
        ...new Set(
          this.blogs
            .map((blog) => blog.shopId)
            .concat(this.homeShops.map((shop) => shop.id)),
        ),
      ];
      if (!ids.length) {
        return;
      }
      const vouchers = await Promise.allSettled(
        ids.map((id) => api("/voucher/list/" + id)),
      );
      const offers = vouchers.flatMap((item) =>
        item.status === "fulfilled" ? item.value.data || [] : [],
      );
      this.homeOffer =
        offers.find((offer) => offer.orderable !== false && !offer.type) ||
        null;
    },
    async changeFeed(feed) {
      if (feed === this.feed) {
        return;
      }
      if (feed === "follow" && !this.requireUser()) {
        return;
      }
      this.feed = feed;
      this.pageError = "";
      await this.loadBlogs(true);
    },
    async loadBlogs(reset = false) {
      if (this.blogLoading) {
        return;
      }
      this.blogLoading = true;
      if (reset) {
        this.blogs = [];
        this.blogPage = 1;
        this.feedLastId = Date.now();
        this.feedOffset = 0;
      }
      try {
        let endpoint = "/blog/hot";
        let params = { current: this.blogPage };
        if (this.page === "profile") {
          endpoint = this.isOwnProfile ? "/blog/of/me" : "/blog/of/user";
          params.id = query.get("id");
        } else if (this.feed === "follow") {
          if (!this.requireUser()) {
            return;
          }
          endpoint = "/blog/of/follow";
          params = { lastId: this.feedLastId, offset: this.feedOffset };
        }
        const result = await api(endpoint, { params });
        const rows =
          this.feed === "follow" && this.page !== "profile"
            ? (result.data && result.data.list) || []
            : result.data || [];
        if (this.page === "profile" && this.profileUser) {
          rows.forEach((blog) => {
            blog.name = blog.name || this.profileUser.nickName;
            blog.icon = blog.icon || this.profileUser.icon;
          });
        }
        this.blogs = this.blogs.concat(rows);
        this.blogHasMore = rows.length > 0;
        this.blogPage += 1;
        if (result.data && result.data.minTime != null) {
          this.feedLastId = result.data.minTime;
          this.feedOffset = result.data.offset;
        }
      } catch (error) {
        this.pageError = error.message;
      } finally {
        this.blogLoading = false;
      }
    },
    async like(blog) {
      if (!this.requireUser() || blog.busy) {
        return;
      }
      this.$set(blog, "busy", true);
      try {
        await api("/blog/like/" + blog.id + "/state", {
          method: "PUT",
          params: { liked: !blog.isLike },
        });
        const fresh = (await api("/blog/" + blog.id)).data;
        this.$set(blog, "liked", fresh.liked);
        this.$set(blog, "isLike", fresh.isLike);
      } catch (error) {
        this.showError(error);
      } finally {
        this.$set(blog, "busy", false);
      }
    },
    async loadCatalog(reset = false) {
      if (this.catalogLoading && !reset) {
        return;
      }
      const sequence = ++this.catalogSequence;
      if (reset) {
        this.catalogPage = 1;
        this.shops = [];
      }
      this.catalogLoading = true;
      this.pageError = "";
      try {
        const result = await api("/shop/catalog", {
          params: {
            keyword: query.get("keyword") || "",
            typeId: this.catalogType,
            page: this.catalogPage,
            size: 12,
            sort: this.catalogSort,
          },
        });
        if (sequence !== this.catalogSequence) {
          return;
        }
        this.shops = this.shops.concat(result.data || []);
        this.catalogTotal = Number(result.total || 0);
        this.catalogPage += 1;
      } catch (error) {
        if (sequence === this.catalogSequence) {
          this.pageError = error.message;
        }
      } finally {
        if (sequence === this.catalogSequence) {
          this.catalogLoading = false;
        }
      }
    },
    async loadShop() {
      const id = query.get("id");
      if (!/^\d+$/.test(id || "")) {
        throw new Error("This shop link is invalid. Please open it again from the shop list.");
      }
      this.shop = (await api("/shop/" + id)).data;
      if (!this.shop) {
        throw new Error("We couldn't find this shop.");
      }
      const results = await Promise.allSettled([
        api("/voucher/list/" + id),
        api("/blog/of/shop", { params: { shopId: id, current: 1 } }),
      ]);
      if (results[0].status === "fulfilled") {
        this.vouchers = results[0].value.data || [];
      } else {
        this.notify("Drops and deals couldn't load. Refresh to try again.", "error");
      }
      if (results[1].status === "fulfilled") {
        this.shopBlogs = results[1].value.data || [];
      }
    },
    async loadBlog() {
      const id = query.get("id");
      if (!/^\d+$/.test(id || "")) {
        throw new Error("This review link is invalid. Please open it again from the home page.");
      }
      this.blog = (await api("/blog/" + id)).data;
      if (!this.blog) {
        throw new Error("We couldn't find this review.");
      }
      try {
        this.blogShop = (await api("/shop/" + this.blog.shopId)).data;
      } catch {
        /* The review remains readable if its shop is unavailable. */
      }
      if (this.user && String(this.user.id) !== String(this.blog.userId)) {
        this.following = Boolean(
          (await api("/follow/or/not/" + this.blog.userId)).data,
        );
      }
    },
    async toggleFollow() {
      if (!this.requireUser() || this.followingBusy) {
        return;
      }
      this.followingBusy = true;
      try {
        await api("/follow/" + this.blog.userId + "/" + !this.following, {
          method: "PUT",
        });
        this.following = !this.following;
        this.notify(this.following ? "You're now following this reviewer." : "Unfollowed.");
      } catch (error) {
        this.showError(error);
      } finally {
        this.followingBusy = false;
      }
    },
    async loadOffers(initial = false) {
      if (this.offerLoading) {
        return;
      }
      this.offerLoading = true;
      try {
        const result = await api("/shop/catalog", {
          params: { page: this.offerShopsPage, size: 20, sort: "score" },
        });
        const shops = result.data || [];
        this.offerShopsTotal = Number(result.total || 0);
        let failures = 0;
        // Bound parallel requests when listing vouchers across many shops.
        for (let index = 0; index < shops.length; index += 4) {
          const rows = await Promise.allSettled(
            shops.slice(index, index + 4).map(async (shop) => {
              const vouchers =
                (await api("/voucher/list/" + shop.id)).data || [];
              return vouchers
                .filter((voucher) => voucher.orderable !== false)
                .map((voucher) => ({ ...voucher, shopName: shop.name }));
            }),
          );
          rows.forEach((row) => {
            if (row.status === "fulfilled") {
              this.offers.push(...row.value);
            } else {
              failures += 1;
            }
          });
        }
        if (failures) {
          this.notify("Some shops' drops and deals didn't load. Refresh to try again.", "error");
        }
        this.offerHasMore = this.offerShopsPage * 20 < this.offerShopsTotal;
        this.offerShopsPage += 1;
      } catch (error) {
        this.showError(error);
        if (initial === true) {
          throw error;
        }
      } finally {
        this.offerLoading = false;
      }
    },
    claim(voucher) {
      if (!this.requireUser() || this.claimBusy) {
        return;
      }
      this.dialog = { kind: "claim", voucher };
    },
    async confirmClaim() {
      const voucher = this.dialog.voucher;
      this.dialog = null;
      this.claimBusy = String(voucher.id);
      try {
        const result = await api(
          "/voucher-order/" +
            (voucher.type ? "seckill/" : "ordinary/") +
            voucher.id,
          { method: "POST" },
        );
        location.assign(
          "/orders.html" +
            (voucher.type
              ? "?request=" + encodeURIComponent(String(result.data))
              : ""),
        );
      } catch (error) {
        if (!error.status || error.status >= 500) {
          try {
            const existing = (
              await api("/voucher-order/of/voucher/" + voucher.id)
            ).data;
            if (existing) {
              location.assign("/orders.html");
              return;
            }
          } catch {
            /* Leave the uncertain result visible; do not automatically resubmit. */
          }
          this.notify("We couldn't confirm the result yet. Check My orders before trying again.", "error");
        } else {
          this.showError(error);
        }
      } finally {
        this.claimBusy = "";
      }
    },
    async loadProfile() {
      this.profileUser = this.isOwnProfile
        ? this.user
        : (await api("/user/" + query.get("id"))).data;
      if (!this.profileUser) {
        throw new Error("This user has no public profile yet.");
      }
      if (this.isOwnProfile) {
        this.signCount = Number((await api("/user/sign/count")).data || 0);
      }
      await this.loadBlogs(true);
    },
    async sign() {
      if (!this.requireUser() || this.signBusy) {
        return;
      }
      this.signBusy = true;
      try {
        await api("/user/sign", { method: "POST" });
        this.signCount = Number((await api("/user/sign/count")).data || 0);
        this.signed = true;
        this.notify("Checked in. See you tomorrow!");
      } catch (error) {
        this.showError(error);
      } finally {
        this.signBusy = false;
      }
    },
    async logout() {
      try {
        await api("/user/logout", {
          method: "POST",
          quiet: true,
          body: { refreshToken: sessionStorage.getItem("refreshToken") },
        });
      } catch (error) {
        if (error.status !== 401) {
          this.showError(error);
          return;
        }
      }
      clearSession();
      location.assign("/");
    },
    async loadOrders() {
      if (this.orderLoading) {
        return;
      }
      this.orderLoading = true;
      this.orderError = "";
      try {
        const result = await api("/voucher-order/mine", {
          params: { page: this.orderPage },
        });
        this.orders = result.data || [];
        this.orderTotal = Number(result.total || 0);
      } catch (error) {
        this.orderError = error.message;
      } finally {
        this.orderLoading = false;
      }
    },
    async orderPageTo(pageNumber) {
      this.orderPage = pageNumber;
      await this.loadOrders();
    },
    async watchRequest(attempt = 0) {
      const id = query.get("request");
      if (!id || !/^\d+$/.test(id)) {
        return;
      }
      try {
        this.pendingRequest = (await api("/voucher-order/requests/" + id)).data;
        const state = this.pendingRequest && this.pendingRequest.state;
        if (state === "SUCCESS") {
          this.requestMessage = "Confirmed! Your order is below.";
          await this.loadOrders();
          return;
        }
        if (state === "FAILED") {
          this.requestMessage = "This reservation didn't go through. Head back to the shop to check availability.";
          return;
        }
        if (state === "REVIEW") {
          this.requestMessage = "We're still verifying this reservation. Check back shortly.";
          return;
        }
        this.requestMessage = "Request received. Confirming your reservation…";
        if (attempt < 10) {
          this.requestTimer = setTimeout(
            () => this.watchRequest(attempt + 1),
            2000,
          );
        } else {
          this.requestMessage = "Your order is still processing. Refresh later and please don't submit again.";
        }
      } catch (error) {
        this.requestMessage = error.message;
      }
    },
    cancelOrder(order) {
      this.dialog = { kind: "cancel", order };
    },
    async confirmCancel() {
      const order = this.dialog.order;
      this.dialog = null;
      try {
        await api("/voucher-order/" + order.order_id + "/cancel", {
          method: "POST",
        });
        await this.loadOrders();
        this.notify("Order cancelled.");
      } catch (error) {
        this.showError(error);
      }
    },
    async sendCode() {
      this.loginError = "";
      if (!EMAIL_PATTERN.test(this.email.trim())) {
        this.loginError = "Please enter a valid email address.";
        return;
      }
      if (this.codeBusy || this.codeCountdown) {
        return;
      }
      this.codeBusy = true;
      try {
        await api("/user/code", {
          method: "POST",
          params: { email: this.email.trim() },
        });
        this.codeCountdown = 60;
        this.codeTimer = setInterval(() => {
          this.codeCountdown -= 1;
          if (this.codeCountdown <= 0) {
            clearInterval(this.codeTimer);
          }
        }, 1000);
        this.notify("Code sent! Check your inbox. In local dev, open Mailpit at http://127.0.0.1:8025.");
      } catch (error) {
        this.loginError = error.message;
      } finally {
        this.codeBusy = false;
      }
    },
    async login() {
      if (
        !EMAIL_PATTERN.test(this.email.trim()) ||
        !/^\d{6}$/.test(this.code.trim())
      ) {
        this.loginError = "Enter a valid email and the 6-digit code.";
        return;
      }
      this.loginBusy = true;
      this.loginError = "";
      try {
        const result = await api("/user/login", {
          method: "POST",
          body: { email: this.email.trim(), code: this.code.trim() },
        });
        storeSession(result.data);
        location.assign(safeReturn(query.get("return"), location.origin));
      } catch (error) {
        this.loginError = error.message;
      } finally {
        this.loginBusy = false;
      }
    },
    async loadPublishShops() {
      if (this.publishShopLoading) {
        return;
      }
      this.publishShopLoading = true;
      try {
        this.publishShops =
          (
            await api("/shop/catalog", {
              params: {
                keyword: this.shopQuery.trim(),
                size: 20,
                sort: "score",
              },
            })
          ).data || [];
      } catch (error) {
        this.showError(error);
      } finally {
        this.publishShopLoading = false;
      }
    },
    async upload(event) {
      const files = Array.from(event.target.files || []);
      if (files.length + this.draft.pictures.length > 9) {
        this.notify("You can upload up to 9 photos.", "error");
        event.target.value = "";
        return;
      }
      if (
        files.some(
          (file) =>
            file.size > 5000000 || !/\.(jpg|jpeg|png|webp)$/i.test(file.name),
        )
      ) {
        this.notify("Choose JPG, PNG or WebP images up to 5 MB each.", "error");
        event.target.value = "";
        return;
      }
      this.uploadBusy = true;
      try {
        for (const file of files) {
          const body = new FormData();
          body.append("file", file);
          const result = await api("/upload/blog", { method: "POST", body });
          this.draft.pictures.push(String(result.data));
        }
      } catch (error) {
        this.showError(error);
      } finally {
        this.uploadBusy = false;
        event.target.value = "";
      }
    },
    async removePicture(path) {
      try {
        await api("/upload/blog", { method: "DELETE", params: { name: path } });
        this.draft.pictures = this.draft.pictures.filter(
          (item) => item !== path,
        );
      } catch (error) {
        this.showError(error);
      }
    },
    async saveBlog() {
      if (
        !this.draft.title.trim() ||
        !this.draft.content.trim() ||
        !this.draft.shopId
      ) {
        this.notify("Add a title, your review and the shop it's about.", "error");
        return;
      }
      if (this.publishBusy || this.uploadBusy) {
        return;
      }
      this.publishBusy = true;
      try {
        const result = await api("/blog", {
          method: "POST",
          body: {
            title: this.draft.title.trim(),
            content: this.draft.content.trim(),
            shopId: this.draft.shopId,
            images: this.draft.pictures.join(","),
          },
        });
        this.draftSaved = true;
        location.assign("/blog-detail.html?id=" + result.data);
      } catch (error) {
        this.showError(error);
      } finally {
        this.publishBusy = false;
      }
    },
  },
});

window.addEventListener("beforeunload", (event) => {
  if (
    page === "publish" &&
    !app.draftSaved &&
    (app.draft.title || app.draft.content || app.draft.pictures.length)
  ) {
    event.preventDefault();
    event.returnValue = "";
  }
});
