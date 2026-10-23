#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import re
import zipfile
from pathlib import Path


SOURCE = Path("app/src/main/assets/GLASS_WAVE.html")
APK_ENTRY = "assets/GLASS_WAVE.html"

BASE_SIZE = 531_623
BASE_SHA256 = (
    "6c715249c7331a45b259aec5c99275eb"
    "9d2ccc6f728340d0bb0d6011598a7b2c"
)

STAGE1_SIZE = 525_793
STAGE1_SHA256 = (
    "05a06fc359d5a517900c0309ca5c07ee"
    "dead1c3683f5e633b19c572338067a37"
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def replace_once(text: str, before: str, after: str) -> str:
    count = text.count(before)

    if count != 1:
        raise RuntimeError(
            "Expected exactly one marker, "
            f"found {count}:\n{before[:160]}"
        )

    return text.replace(before, after, 1)


def remove_before(text: str, start: str, end: str) -> str:
    start_count = text.count(start)
    end_count = text.count(end)

    if start_count != 1:
        raise RuntimeError(
            "Stage-1 start marker count "
            f"is {start_count}:\n{start[:160]}"
        )

    if end_count != 1:
        raise RuntimeError(
            "Stage-1 end marker count "
            f"is {end_count}:\n{end[:160]}"
        )

    left = text.index(start)
    right = text.index(end)

    if right <= left:
        raise RuntimeError(
            "Stage-1 source markers are out of order"
        )

    return text[:left] + text[right:]


def transform_html(text: str) -> str:
    # Audio Microscope карточкасы жойылады.
    # Vocal / Instruments карточкасы оның орнына
    # document flow арқылы бірден көтеріледі.
    text = remove_before(
        text,
        (
            '<div class="settings-card">'
            '<div class="settings-card-head">'
            '<div><div class="settings-kicker">'
            'AUDIO MICROSCOPE · LIVE</div>'
        ),
        (
            '<div class="settings-card">'
            '<div class="settings-card-head">'
            '<div><div class="settings-kicker">'
            'LIVE M/S STEMS · EXPERIMENT</div>'
        ),
    )

    # Sound Lab тақырыбы мен карточкасы жойылады.
    # GLASS CORE бірден келесі section болады.
    text = remove_before(
        text,
        (
            '<div class="section-head" '
            'style="margin:22px 2px 12px">'
            '<div><div class="sub">'
            'LIVE SIGNAL BENCH</div>'
            '<h2>Sound Lab</h2></div></div>'
        ),
        (
            '<div class="section-head" '
            'style="margin:22px 2px 12px">'
            '<div><div class="sub">'
            'GLASS SIGNAL MATRIX</div>'
            '<h2>GLASS CORE</h2></div></div>'
        ),
    )

    # Microscope және Sound Lab функциялары,
    # analyser және екі RAF цикл түгел жойылады.
    text = remove_before(
        text,
        "function drawMicroscope(){",
        "function coreOpen(panel){",
    )

    replacements = (
        (
            (
                "let stemVocalValue=0,"
                "stemInstValue=0,"
                "microFrozen=false;"
            ),
            (
                "let stemVocalValue=0,"
                "stemInstValue=0;"
            ),
        ),
        (
            (
                "function coreOpen(panel){"
                "if(panel==='soundLabPanel'||"
                "panel==='corePanel')"
                "panel='labPanel';"
            ),
            (
                "function coreOpen(panel){"
                "if(panel==='corePanel')"
                "panel='labPanel';"
            ),
        ),
        (
            (
                "if($('microFreeze'))"
                "$('microFreeze')"
                ".addEventListener('click',()=>{"
                "microFrozen=true;"
                "$('microFreeze')"
                ".classList.add('on')});"
            ),
            "",
        ),
        (
            (
                "if($('microReset'))"
                "$('microReset')"
                ".addEventListener('click',()=>{"
                "microFrozen=false;"
                "$('microFreeze')"
                ".classList.remove('on');"
                "drawMicroscope()});"
            ),
            "",
        ),
        (
            (
                "document.querySelectorAll("
                "'[data-sl-open]')"
                ".forEach(b=>"
                "b.addEventListener('click',()=>"
                "coreOpen(b.dataset.slOpen)));"
            ),
            "",
        ),
        (
            (
                "initLabSettings();"
                "updateSmartEqUI();"
                "requestAnimationFrame("
                "microscopeTick);"
                "requestAnimationFrame("
                "soundLabTick);"
                "setInterval(()=>{"
                "if(labOpen())coreState()"
                "},350);"
                "coreState();"
            ),
            (
                "initLabSettings();"
                "updateSmartEqUI();"
                "setInterval(()=>{"
                "if(labOpen())coreState()"
                "},750);"
                "coreState();"
            ),
        ),
        (
            (
                "hrDspRefresh();"
                "updateSmartEqUI();"
                "updateSoundLab();"
                "coreState();"
            ),
            (
                "hrDspRefresh();"
                "updateSmartEqUI();"
                "coreState();"
            ),
        ),

        # EQ/8D DSP әлі analyser-ге тәуелді,
        # бірақ жеңілірек FFT жеткілікті.
        ("analyserNode.fftSize=2048", "analyserNode.fftSize=1024"),

        # 3D Scene canvas: ~35 FPS → ~20 FPS.
        ("if(ts-last<28)return", "if(ts-last<50)return"),

        # Player UI sync.
        ("setInterval(sync,400)", "setInterval(sync,800)"),

        # Smart EQ spectrum canvas.
        (
            (
                "if(ts-last<45||"
                "document.hidden||"
                "fx()||!window.nwFx)"
                "return"
            ),
            (
                "if(ts-last<100||"
                "document.hidden||"
                "fx()||!window.nwFx)"
                "return"
            ),
        ),
    )

    for before, after in replacements:
        text = replace_once(text, before, after)

    return text


def update_integrity_consumers() -> None:
    paths = (
        Path(
            "app/src/main/java/"
            "kz/glasswave/player/"
            "MainActivity.kt"
        ),
        Path("app/build.gradle.kts"),
    )

    old_a = BASE_SHA256[:32]
    old_b = BASE_SHA256[32:]

    new_a = STAGE1_SHA256[:32]
    new_b = STAGE1_SHA256[32:]

    for path in paths:
        if not path.is_file():
            continue

        text = path.read_text(encoding="utf-8")
        original = text

        # Бір жолдағы hash.
        text = text.replace(BASE_SHA256, STAGE1_SHA256)

        # Kotlin/Gradle ішіндегі екіге бөлінген hash.
        text = text.replace(old_a, new_a)
        text = text.replace(old_b, new_b)

        # MainActivity constants.
        text = re.sub(
            r"(EXPECTED_(?:HTML_)?SIZE\s*=\s*)531_?623",
            rf"\g<1>{STAGE1_SIZE}",
            text,
        )

        # Gradle verification task.
        text = re.sub(
            r"(expectedSize\s*=\s*)531_?623(L?)",
            rf"\g<1>{STAGE1_SIZE}\2",
            text,
        )

        if text != original:
            path.write_text(text, encoding="utf-8", newline="")


def prepare_source(path: Path) -> bytes:
    if not path.is_file():
        raise FileNotFoundError(
            f"Missing source asset: {path}"
        )

    data = path.read_bytes()
    size = len(data)
    digest = sha256(data)

    # Idempotent: Actions retry кезінде қайта кеспейді.
    if size == STAGE1_SIZE and digest == STAGE1_SHA256:
        update_integrity_consumers()
        return data

    # Белгісіз HTML-ды үнсіз бұзбайды.
    if size != BASE_SIZE or digest != BASE_SHA256:
        raise RuntimeError(
            "Unexpected GLASS_WAVE.html baseline:\n"
            f"size={size}\n"
            f"sha256={digest}"
        )

    transformed = transform_html(
        data.decode("utf-8")
    ).encode("utf-8")

    if len(transformed) != STAGE1_SIZE:
        raise RuntimeError(
            "Stage-1 size mismatch:\n"
            f"{len(transformed)} != {STAGE1_SIZE}"
        )

    transformed_hash = sha256(transformed)

    if transformed_hash != STAGE1_SHA256:
        raise RuntimeError(
            "Stage-1 SHA-256 mismatch:\n"
            f"{transformed_hash}\n"
            f"!= {STAGE1_SHA256}"
        )

    # Actions checkout ішіндегі HTML
    # физикалық түрде осы жерде ауысады.
    path.write_bytes(transformed)
    update_integrity_consumers()
    return transformed


def write_report(text: str, report: str | None) -> None:
    if not report:
        return

    target = Path(report)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def source_report(origin: str, data: bytes) -> str:
    return "\n".join(
        (
            "GLASS WAVE Stage-1 source verification",
            f"Origin: {origin}",
            f"Expected size: {STAGE1_SIZE}",
            f"Actual size: {len(data)}",
            f"Expected SHA-256: {STAGE1_SHA256}",
            f"Actual SHA-256: {sha256(data)}",
            (
                "RESULT: OK — source-level Lab cleanup "
                "and thermal profile applied"
            ),
            "",
        )
    )


def apk_report(origin: str, data: bytes, passed: bool) -> str:
    result = (
        "RESULT: OK — APK contains the Stage-1 HTML"
        if passed
        else "RESULT: FAIL — APK does not contain the Stage-1 HTML"
    )

    return "\n".join(
        (
            "GLASS WAVE Stage-1 APK verification",
            f"Origin: {origin}",
            f"Expected size: {STAGE1_SIZE}",
            f"Actual size: {len(data)}",
            f"Expected SHA-256: {STAGE1_SHA256}",
            f"Actual SHA-256: {sha256(data)}",
            result,
            "",
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser()

    subparsers = parser.add_subparsers(
        dest="command", required=True
    )

    source = subparsers.add_parser("source")
    source.add_argument("--path", type=Path, default=SOURCE)
    source.add_argument("--report")

    apk = subparsers.add_parser("apk")
    apk.add_argument("apk_path", type=Path)
    apk.add_argument("--report")

    args = parser.parse_args()

    if args.command == "source":
        data = prepare_source(args.path)
        output = source_report(args.path.as_posix(), data)
        print(output, end="")
        write_report(output, args.report)
        return

    if not args.apk_path.is_file():
        raise FileNotFoundError(
            f"Missing APK: {args.apk_path}"
        )

    with zipfile.ZipFile(args.apk_path) as archive:
        if APK_ENTRY not in archive.namelist():
            raise RuntimeError(
                f"Missing APK entry: {APK_ENTRY}"
            )
        data = archive.read(APK_ENTRY)

    passed = (
        len(data) == STAGE1_SIZE
        and sha256(data) == STAGE1_SHA256
    )

    output = apk_report(
        f"{args.apk_path.as_posix()}!/{APK_ENTRY}",
        data,
        passed,
    )

    print(output, end="")
    write_report(output, args.report)

    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
