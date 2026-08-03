(() => {
  const script = document.currentScript;
  const requestedActive = script?.dataset.minegNav;
  const path = window.location.pathname;

  const active = (requestedActive === "family" ? "private" : requestedActive)
    || (path.includes("/03-private-space/") || path.includes("/05-family-album/") ? "private"
        : path.includes("/06-backup/") || path.includes("/02-permissions-dep/") ? "backup"
          : path.includes("/08-profile/") || path.includes("/07-recycle-bin/") ? "profile"
            : "");

  if (!active || !script) return;

  const styleHref = new URL("mineg-navigation.css", script.src).href;
  if (!document.querySelector(`link[href="${styleHref}"]`)) {
    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = styleHref;
    document.head.appendChild(link);
  }

  const expectedLabels = ["私人空间", "备份", "我的"];
  const candidates = [...document.querySelectorAll("nav")].filter((nav) => {
    const copy = nav.textContent || "";
    const labelMatches = expectedLabels.filter((label) => copy.includes(label)).length;
    const styles = window.getComputedStyle(nav);
    const isBottomFixed = styles.position === "fixed" && parseFloat(styles.bottom || "999") <= 1;
    return nav.classList.contains("bottom-nav") || labelMatches >= 2 || isBottomFixed;
  });

  const existingNav = candidates.at(-1);
  if (!existingNav) return;

  const previousStyles = window.getComputedStyle(existingNav);
  const shouldMute = previousStyles.pointerEvents === "none"
    || previousStyles.filter !== "none"
    || Number.parseFloat(previousStyles.opacity || "1") < 0.75;

  const rootStyles = window.getComputedStyle(document.documentElement);
  const cssPrimary = rootStyles.getPropertyValue("--primary").trim();
  const tailwindPrimary = window.tailwind?.config?.theme?.extend?.colors?.primary;
  const textProbe = document.querySelector(".text-primary");
  const backgroundProbe = document.querySelector(".bg-primary");
  const textPrimary = textProbe ? window.getComputedStyle(textProbe).color : "";
  const backgroundPrimary = backgroundProbe ? window.getComputedStyle(backgroundProbe).backgroundColor : "";
  const isVisibleColor = (value) => value && value !== "rgba(0, 0, 0, 0)" && value !== "transparent";
  const accent = isVisibleColor(cssPrimary)
    ? cssPrimary
    : isVisibleColor(tailwindPrimary)
      ? tailwindPrimary
      : isVisibleColor(textPrimary)
        ? textPrimary
        : isVisibleColor(backgroundPrimary)
          ? backgroundPrimary
          : "#3BAAFF";

  const iconUrls = {
    private: new URL("icons/cloud.png", script.src).href,
    backup: new URL("icons/local-album.png", script.src).href,
    profile: new URL("icons/profile.png", script.src).href,
  };

  const destinations = {
    private: "../../03-private-space/01-private-space-overview/index.html",
    backup: "../../06-backup/03-backup-uploading/index.html",
    profile: "../../08-profile/01-profile-overview/index.html",
  };

  const items = [
    ["private", "私人空间"],
    ["backup", "备份"],
    ["profile", "我的"],
  ];

  existingNav.className = `mineg-main-nav${shouldMute ? " mineg-main-nav--muted" : ""}`;
  existingNav.setAttribute("aria-label", "主导航");
  existingNav.style.setProperty("--mineg-nav-accent", accent);
  existingNav.innerHTML = items.map(([key, label]) => {
    const selected = key === active;
    return `
      <a class="mineg-nav-item${selected ? " is-active" : ""}"
         href="${destinations[key]}" aria-label="${label}"${selected ? " aria-current=\"page\"" : ""}>
        <img class="mineg-nav-icon" src="${iconUrls[key]}" alt="" aria-hidden="true" />
      </a>`;
  }).join("");
})();
