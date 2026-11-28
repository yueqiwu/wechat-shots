/* ============================================================
   聊天截图导出 · 官网交互
   - 滚动揭示动效（IntersectionObserver）
   - 明暗主题切换（localStorage 记忆）
   - version.json 驱动版本号 / 更新说明 / 下载链接
   ============================================================ */
(function () {
  "use strict";

  /* ---------- 主题 ---------- */
  var THEME_KEY = "wcs-theme";
  var root = document.documentElement;
  var toggle = document.querySelector("[data-theme-toggle]");

  function applyTheme(theme) {
    if (theme === "dark" || theme === "light") {
      root.setAttribute("data-theme", theme);
    } else {
      root.removeAttribute("data-theme");
    }
    if (toggle) {
      var dark = theme === "dark" ||
        (!theme && window.matchMedia("(prefers-color-scheme: dark)").matches);
      toggle.setAttribute("aria-label", dark ? "切换到浅色模式" : "切换到深色模式");
      toggle.setAttribute("title", dark ? "切换到浅色模式" : "切换到深色模式");
    }
  }

  var saved = null;
  try { saved = localStorage.getItem(THEME_KEY); } catch (e) { /* 隐私模式 */ }

  // 支持 ?theme=dark / ?theme=light 强制主题（分享链接、截图测试用）
  var forced = null;
  try {
    var q = new URLSearchParams(window.location.search).get("theme");
    if (q === "dark" || q === "light") forced = q;
  } catch (e) { /* 老浏览器 */ }

  applyTheme(forced || saved);

  if (toggle) {
    toggle.addEventListener("click", function () {
      var current = root.getAttribute("data-theme");
      if (!current) {
        current = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
      }
      var next = current === "dark" ? "light" : "dark";
      try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* ignore */ }
      applyTheme(next);
    });
  }

  /* ---------- 导航滚动态 ---------- */
  var nav = document.querySelector(".nav");
  function onScroll() {
    if (!nav) return;
    if (window.scrollY > 8) nav.classList.add("scrolled");
    else nav.classList.remove("scrolled");
  }
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  /* ---------- 滚动揭示 ---------- */
  var revealNodes = Array.prototype.slice.call(document.querySelectorAll(".reveal"));
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  if (!("IntersectionObserver" in window) || reduceMotion) {
    revealNodes.forEach(function (el) { el.classList.add("in"); });
  } else {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("in");
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -8% 0px" });
    revealNodes.forEach(function (el) { io.observe(el); });
  }

  /* ---------- 年份 ---------- */
  var yearEl = document.getElementById("year");
  if (yearEl) yearEl.textContent = String(new Date().getFullYear());

  /* ---------- version.json ---------- */
  var VERSION_URL = "version.json";

  function fmtSize(bytes) {
    if (!bytes || isNaN(bytes)) return "";
    var mb = bytes / 1024 / 1024;
    return "约 " + Math.round(mb) + " MB";
  }

  function fmtDate(iso) {
    if (!iso) return "";
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    return d.getFullYear() + " 年 " + (d.getMonth() + 1) + " 月 " + d.getDate() + " 日";
  }

  function applyVersion(info) {
    if (!info || !info.versionName) return;

    var name = String(info.versionName);
    var code = info.versionCode != null ? String(info.versionCode) : "";

    document.querySelectorAll("[data-version-name]").forEach(function (el) {
      el.textContent = "v" + name;
    });
    document.querySelectorAll("[data-version-code]").forEach(function (el) {
      el.textContent = code ? "(" + code + ")" : "";
    });

    var apk = info.apk || "";
    document.querySelectorAll("[data-download]").forEach(function (el) {
      if (apk) el.setAttribute("href", apk);
    });

    var sizeText = fmtSize(info.size);
    if (sizeText) {
      document.querySelectorAll("[data-apk-size]").forEach(function (el) {
        el.textContent = sizeText;
      });
    }

    if (apk) {
      var m = /([^/\\]+)$/.exec(apk);
      if (m) {
        document.querySelectorAll("[data-apk-name]").forEach(function (el) {
          el.textContent = m[1];
        });
      }
    }
  }

  /* ---------- 电脑版版本清单（record-toolbox-version.json） ---------- */
  var TOOLBOX_VERSION_URL = "record-toolbox-version.json";

  function applyToolboxVersion(info) {
    if (!info || !info.version) return;

    document.querySelectorAll("[data-win-version]").forEach(function (el) {
      el.textContent = info.version;
    });

    var sizeText = fmtSize(info.size);
    if (sizeText) {
      document.querySelectorAll("[data-win-size]").forEach(function (el) {
        el.textContent = sizeText;
      });
    }

    if (info.zip) {
      document.querySelectorAll("[data-win-download]").forEach(function (el) {
        el.setAttribute("href", info.zip);
      });
    }

    var pub = document.getElementById("pubDate");
    if (pub) {
      var dText = fmtDate(info.publishedAt);
      if (dText) pub.textContent = "发布于 " + dText;
    }

    var notesEl = document.getElementById("vcNotes");
    if (notesEl && Array.isArray(info.notes) && info.notes.length) {
      notesEl.innerHTML = "";
      info.notes.forEach(function (text) {
        var li = document.createElement("li");
        li.textContent = text;
        notesEl.appendChild(li);
      });
    }
  }

  if (window.fetch) {
    var ctrl = window.AbortController ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, 5000);
    fetch(VERSION_URL, ctrl ? { signal: ctrl.signal, cache: "no-store" } : { cache: "no-store" })
      .then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(function (info) { applyVersion(info); })
      .catch(function () { /* 静默：使用页面内置默认版本信息 */ })
      .then(function () { clearTimeout(timer); });
  }

  /* ---------- 电脑版版本清单（record-toolbox-version.json） ---------- */
  if (window.fetch) {
    var ctrlWin = window.AbortController ? new AbortController() : null;
    var timerWin = setTimeout(function () { if (ctrlWin) ctrlWin.abort(); }, 5000);
    fetch(TOOLBOX_VERSION_URL, ctrlWin ? { signal: ctrlWin.signal, cache: "no-store" } : { cache: "no-store" })
      .then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(function (info) { applyToolboxVersion(info); })
      .catch(function () { /* 静默：使用页面内置默认版本信息 */ })
      .then(function () { clearTimeout(timerWin); });
  }
})();
