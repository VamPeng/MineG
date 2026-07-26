(() => {
    const pageState = document.body.dataset.state || "uploading";
    const storageKey = "mineg-auto-backup";
    const query = new URLSearchParams(window.location.search);
    const requestedSetting = query.get("autoBackup");

    if (requestedSetting === "0" || requestedSetting === "1") {
        localStorage.setItem(storageKey, requestedSetting);
    }

    const autoBackupEnabled = localStorage.getItem(storageKey) !== "0";

    const images = [
        "https://lh3.googleusercontent.com/aida-public/AB6AXuDhmqSlNB97JLa6iEpG7z-IWlDeKlX_-_ZVDKqpqbhnN5Fyzvqr4vDjNVAvRGV8fHuWhdAtMCGImtYIr27oFjwsmoTsHVnbS3vXs8nye0HTP2gKZl7jFp1Dssu3MAtKDpc3-G337eG2gMS6pvOvYzqM55VDgTNpK51-9cs-TN8Z2ybjsbfyJt_Hl6juow3s9Yi6z-II6mgzuSTYUXSEnwhq8ogNCvLNWh5U7cL8usPClmsjbMsFF3UFfSuH2ucS3FrcWlBDbLF4S34",
        "https://lh3.googleusercontent.com/aida-public/AB6AXuAXAAzRhQw1SRemAkURIw_ognpbPAlSUeckAyGPN11gBdUBEUOWAuIIfiBBW5SvITO7qS3EH8hd98z9RH877AYplxuEQvueRemQrxOYmJhhWfoQ5Zp3jF3eLgKDGmTPN3I4cHEF-AIfj6Pqbk_sVB4mKQ6yxJwPS29FdKIq41scj5GHyZ3RE2mj4W5oaUgxaMObjwcwmSlzFBwG7Tn4ufP9TnN9ovMVRV6N4gvep4Job_MgLU93m5ofP0KhKY6MMHs3V5OFCeJvE9w",
        "https://lh3.googleusercontent.com/aida-public/AB6AXuCMasbvdQnm31fPAfCRTz5QUhvizWp6ciwkH03XrXAHakB1qbXTwOh_etxVZW8l4e8f3W-Jh-iOdUhdGmp5mwvtX507_-MZoE7Z6CdS0IsKNN06le8T9sgnJc9xNsOkvut3y2l9E-ZIB2tQ6vWgJp4wp5vTFb3JGUZ8NvDkIIs6MEqLlszBXUSp5bdN6VVWzgqZpg2cGJ31zDRfJDMmBOFLE6Ap2Xi1-pWx7JM9dKSp80_emtNP2UyZmASKLu-k2LudsWY1gX7jBrw",
        "https://lh3.googleusercontent.com/aida-public/AB6AXuCWa_AjUSKkyYgvsN4L2DBrEKu1LEXTbrtSWaiw_qar3pGxNnzLrfbKyY2NGzOXcywY3O_4EzKEAeYsjbLIpe0DdTcNv4npp61POAEh0hCwWED2mwm2Xqn-hF2o-JpwI5aF4MbHv3Wi0yFw1-FjhYhxEzQwRv9DNTZ2ZIX1Jg6SgtfazpeuIjwewsRA9dxi9gZbjpf4Xwoia5R7o3Nizu9W9pgVsXgMbMzqyWKvF6I5gzAoZTDwpaltqGHgaxVYgQDdNKuXs9MHLFs",
        "https://lh3.googleusercontent.com/aida-public/AB6AXuBD2o57mC5xK_ypxYxcoB1-sDD0A0kt77wpidD6APJgGdKwZSh_82GjAyoCOvROkTHW8YMwOq1Id1WcJdNdD9MCsyqCGWscDJzOYgiGS3VOjkIOj47xVG2zlkSCl3ttI4WZFLlWM0E-MbWpZUnsBAMpAU2ScS5fY29nX7S_-_urD5ktOHyEg1dUJqS-tqDja-7SSHsnXlWBl3AYY7q3W7W9PZitvxH3_PXceWZSMN26Jz35eOVh0D6lx8OQAE1xOlQgzrFebaBZZI4",
        "https://lh3.googleusercontent.com/aida-public/AB6AXuA2SRlvmKtHNMmzmeCj2vhn6YIyYx3NmSrZDjbXq_rdwHHIBxWJoODLwr1Spy-CqLEJajZ3XU0prvjoHotwuKGFvJvjNIpHzfGsODKPl7NpXMYWvPLMAggWuLNz_f7aOF4yOUCy_uvtPl3byWfCWe0sH8W3oEJUJMxDiaRVoYkddEyA8sC_9GVUhWas30WVcKN55TpoK1E6DBV_qaiuDEl0G7jyfcAqzlnHwZcb3WOnewtGfj4XHkejoJp9zVlAueb1mR269WsVP4U",
        "https://lh3.googleusercontent.com/aida-public/AB6AXuBhkrubUee5IvOpXVMtYvnZANmRDiVJ9htY8vJD5SJwbZ2sZZB6_0Loa1wFOxwDLFNhVyxi2BMcDsW_QvfRIW-LDVodPinp5AfMv2Cks4187tKhdb3gPSgkOCMf3vnS-JrV111KjnsoH3Gll4olCS7FGrUY-SNGp0-xhqOjRjzE7ZiU9MVCY2fG0zUXSuYD8ZvJt7VHhasch5n7oUVzaXGQd9yXkfrg-I7QuTU2jIY3vJeJHm6cvctOlJmgfTPaYCmqgz2QRky3NJI",
        "https://lh3.googleusercontent.com/aida-public/AB6AXuCJ6-SntyQXmDVr0pRwZ6XCWr6BLuu-ZuDeXGRC-IbnzlXVrhDcSzn-pBkjZ_iRuO6OR_bpmdJV2mbInhIQX9JlWJ5C0aK3iNUDudLAJDTNJ4_cXWTzXdpKuVqrXONc1XL-h1Rbqo1EmoNgq31nFkv5u8xj2ztKln6aol0OEbZ6Xc2Fi5wcQP2gt8NpJbM1psUtWpmzYnxmMfRpBwLliPbnhaRbUnG80Wb_HwUEUsfbs4lbiuMTC8C01h50n7eq-n6GErqmxMw_4J8"
    ];

    const states = {
        uploading: {
            chip: "仅 Wi-Fi · 4.8 MB/s",
            chipIcon: "wifi",
            title: "正在同步",
            description: "正在上传「家庭时光」中的 1 项",
            percent: "68%",
            progress: "68%",
            image: images[0],
            className: ""
        },
        scanning: {
            chip: "正在整理",
            chipIcon: "manage_search",
            title: "正在扫描本地媒体",
            description: "已发现 1,204 项，稍后自动开始上传",
            percent: "35%",
            progress: "35%",
            image: images[4],
            className: ""
        },
        waiting: {
            chip: "等待 Wi-Fi",
            chipIcon: "wifi_off",
            title: "同步已暂停",
            description: "连接 Wi-Fi 后将自动继续",
            percent: "68%",
            progress: "68%",
            image: images[0],
            className: "paused"
        },
        offline: {
            chip: "网络不可用",
            chipIcon: "cloud_off",
            title: "暂时无法同步",
            description: "网络恢复后将从当前进度继续",
            percent: "68%",
            progress: "68%",
            image: images[0],
            className: "paused"
        },
        storage: {
            chip: "空间不足",
            chipIcon: "cloud_alert",
            title: "同步需要处理",
            description: "云端空间不足，请清理空间后继续",
            percent: "68%",
            progress: "68%",
            image: images[0],
            className: "error-state"
        },
        service: {
            chip: "服务异常",
            chipIcon: "cloud_off",
            title: "暂时无法连接云端",
            description: "本地媒体仍可浏览，稍后将自动重试",
            percent: "68%",
            progress: "68%",
            image: images[0],
            className: "paused"
        },
        complete: {
            chip: "刚刚更新",
            chipIcon: "cloud_done",
            complete: true
        }
    };

    const albums = [
        { name: "最近项目", count: "1,286 项", imageIndexes: [0, 1, 2, 3, 4, 5], videoIndexes: [2] },
        { name: "家庭时光", count: "238 项", imageIndexes: [6, 7, 0, 2, 5, 1], videoIndexes: [1, 4] },
        { name: "旅行", count: "96 项", imageIndexes: [4, 3, 5, 1, 0, 7], videoIndexes: [] }
    ];

    function mediaTile(imageIndex, isVideo, index) {
        return `
            <button class="media-tile" aria-label="打开本地媒体 ${index + 1}">
                <img alt="本地相册媒体缩略图" src="${images[imageIndex]}" />
                ${isVideo ? `
                    <span class="media-badge" aria-label="视频">
                        <span class="material-symbols-outlined">play_arrow</span>
                    </span>
                ` : ""}
            </button>
        `;
    }

    function albumSection(album) {
        return `
            <section class="album-section">
                <div class="album-heading">
                    <h2>${album.name}</h2>
                    <span class="album-count">${album.count}</span>
                </div>
                <div class="media-grid">
                    ${album.imageIndexes.map((imageIndex, index) =>
                        mediaTile(imageIndex, album.videoIndexes.includes(index), index)
                    ).join("")}
                </div>
            </section>
        `;
    }

    function statusPanel(state) {
        if (state.complete) {
            return `
                <section class="sync-panel">
                    <div class="sync-heading">
                        <p class="eyebrow">同步状态</p>
                        <span class="status-chip">
                            <span class="material-symbols-outlined">${state.chipIcon}</span>
                            ${state.chip}
                        </span>
                    </div>
                    <div class="sync-card complete-card">
                        <div>
                            <div class="complete-icon">
                                <span class="material-symbols-outlined">check_circle</span>
                            </div>
                            <h2>同步完成</h2>
                            <p>本地媒体已全部安全备份</p>
                        </div>
                    </div>
                </section>
            `;
        }

        return `
            <section class="sync-panel ${state.className}">
                <div class="sync-heading">
                    <p class="eyebrow">同步状态</p>
                    <span class="status-chip">
                        <span class="material-symbols-outlined">${state.chipIcon}</span>
                        ${state.chip}
                    </span>
                </div>
                <div class="sync-card" style="--progress: ${state.progress}">
                    <img class="sync-photo" alt="当前正在同步的本地媒体" src="${state.image}" />
                    <div class="sync-shade"></div>
                    <div class="sync-copy">
                        <div class="sync-title-row">
                            <h2 class="sync-title">${state.title}</h2>
                            <span class="sync-percent">${state.percent}</span>
                        </div>
                        <p class="sync-description">${state.description}</p>
                        <div class="progress-track" aria-label="上传进度 ${state.percent}">
                            <div class="progress-value"></div>
                        </div>
                    </div>
                </div>
            </section>
        `;
    }

    function disabledStatusPanel() {
        return `
            <section class="sync-panel paused">
                <div class="sync-heading">
                    <p class="eyebrow">同步状态</p>
                    <span class="status-chip">
                        <span class="material-symbols-outlined">pause_circle</span>
                        自动备份已关闭
                    </span>
                </div>
                <div class="sync-card complete-card">
                    <div>
                        <div class="complete-icon">
                            <span class="material-symbols-outlined">cloud_off</span>
                        </div>
                        <h2>浏览你的本地媒体</h2>
                        <p>点击下方按钮即可开始备份</p>
                    </div>
                </div>
            </section>
        `;
    }

    const selectedState = states[pageState] || states.uploading;
    document.title = `MineG - 本地相册`;
    document.body.classList.toggle("auto-backup-off", !autoBackupEnabled);
    document.body.innerHTML = `
        <div class="app-shell">
            <header class="top-bar">
                <h1>本地相册</h1>
                <a class="icon-button" href="../01-auto-backup-default-on-decision-a/index.html" aria-label="打开备份设置">
                    <span class="material-symbols-outlined">settings</span>
                </a>
            </header>
            <main class="content">
                <div id="sync-status">
                    ${autoBackupEnabled ? statusPanel(selectedState) : disabledStatusPanel()}
                </div>
                <div class="albums">
                    ${albums.map(albumSection).join("")}
                </div>
            </main>
            <button class="start-backup" id="start-backup" type="button">
                <span class="material-symbols-outlined">cloud_upload</span>
                开始备份
            </button>
            <nav class="bottom-nav" aria-label="主导航">
                <a class="nav-item" href="../../03-private-space/01-private-space-overview/index.html">
                    <span class="material-symbols-outlined">shield_person</span>
                    <span>私人空间</span>
                </a>
                <a class="nav-item" href="../../05-family-album/01-family-album-timeline/index.html">
                    <span class="material-symbols-outlined">photo_library</span>
                    <span>家庭相册</span>
                </a>
                <a class="nav-item active" href="../03-backup-uploading/index.html" aria-current="page">
                    <span class="material-symbols-outlined">cloud_upload</span>
                    <span>备份</span>
                </a>
                <a class="nav-item" href="../../08-profile/01-profile-overview/index.html">
                    <span class="material-symbols-outlined">person</span>
                    <span>我的</span>
                </a>
            </nav>
        </div>
    `;

    const startButton = document.getElementById("start-backup");
    startButton.addEventListener("click", () => {
        localStorage.setItem(storageKey, "1");
        document.body.classList.remove("auto-backup-off");
        document.getElementById("sync-status").innerHTML = statusPanel(states.uploading);
        window.history.replaceState({}, "", window.location.pathname);
    });
})();
