  /*
   * GW seek stability.
   *
   * Original HTML sets currentTime on every "input" event.
   * Capture phase blocks that listener and commits one seek
   * only when the user releases the slider.
   */
  (function installStableSeek() {
    const bar = document.getElementById("seekBar");
    const media = document.getElementById("audio");

    if (!bar || !media || bar.dataset.gwStableSeek === "1") {
      return;
    }

    bar.dataset.gwStableSeek = "1";

    let dragging = false;
    let resumeAfterSeek = false;
    let requestedTime = null;

    function preview() {
      if (!Number.isFinite(media.duration) || media.duration <= 0) {
        return;
      }

      requestedTime =
        media.duration * Number(bar.value || 0) / 100;

      const elapsed = document.getElementById("elapsed");

      if (elapsed && typeof fmt === "function") {
        elapsed.textContent = fmt(requestedTime);
      }
    }

    function commit() {
      if (
        requestedTime === null ||
        !Number.isFinite(requestedTime) ||
        !Number.isFinite(media.duration)
      ) {
        dragging = false;
        return;
      }

      const target = Math.max(
        0,
        Math.min(
          requestedTime,
          Math.max(0, media.duration - 0.05)
        )
      );

      dragging = false;
      requestedTime = null;

      const restart = function () {
        media.removeEventListener("seeked", restart);

        if (resumeAfterSeek) {
          media.play().catch(function () {});
        }

        resumeAfterSeek = false;
      };

      media.addEventListener("seeked", restart, { once: true });

      try {
        media.currentTime = target;
      } catch (_) {
        media.removeEventListener("seeked", restart);
        resumeAfterSeek = false;
      }
    }

    bar.addEventListener(
      "pointerdown",
      function () {
        dragging = true;
        resumeAfterSeek = !media.paused && !media.ended;
      },
      true
    );

    bar.addEventListener(
      "touchstart",
      function () {
        dragging = true;
        resumeAfterSeek = !media.paused && !media.ended;
      },
      { capture: true, passive: true }
    );

    /*
     * Capture phase prevents original HTML input handler
     * from issuing repeated media.currentTime assignments.
     */
    document.addEventListener(
      "input",
      function (event) {
        if (event.target !== bar) return;

        event.stopPropagation();
        event.stopImmediatePropagation();

        if (!dragging) {
          dragging = true;
          resumeAfterSeek = !media.paused && !media.ended;
        }

        preview();
      },
      true
    );

    bar.addEventListener("change", commit, true);
    bar.addEventListener("pointerup", commit, true);
    bar.addEventListener("pointercancel", commit, true);
    bar.addEventListener("touchend", commit, true);
  })();
