"""StudyPilot adapter for the MinerU 2.5.4 CPU pipeline."""

import asyncio
import json
import os
import subprocess
import tempfile
import zipfile
from importlib.metadata import version
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from mineru.backend.pipeline.pipeline_middle_json_mkcontent import (
    get_title_level,
    make_blocks_to_markdown,
)
from mineru.cli.common import aio_do_parse
from mineru.utils.enum_class import MakeMode
from starlette.background import BackgroundTask

def configure_formula_batch():
    configured = os.getenv("STUDYPILOT_MINERU_MFR_BATCH_SIZE")
    if configured is None:
        return
    size = int(configured)
    if size < 1 or version("mineru") != "2.5.4":
        raise RuntimeError("STUDYPILOT_MINERU_MFR_BATCH_SIZE requires MinerU 2.5.4 and a positive batch size")
    from mineru.backend.pipeline import batch_analyze

    # 2.5.4 exposes page batches through the environment, while MFR uses a module constant.
    batch_analyze.MFR_BASE_BATCH_SIZE = size


configure_formula_batch()
app = FastAPI(title="StudyPilot MinerU")
gate = asyncio.Semaphore(1)
parser_version = f"mineru-{version('mineru')}-pipeline-v1"
limit = 100 * 1024 * 1024


@app.get("/health")
def health():
    return {"status": "ready", "parserVersion": parser_version}


def image_paths(block):
    for child in block.get("blocks", []):
        yield from image_paths(child)
    for line in block.get("lines", []):
        for span in line["spans"]:
            if span.get("image_path"):
                yield "images/" + span["image_path"]


def render(pages):
    paragraphs, sources, headings = [], [], []
    offset = 0
    for page in pages:
        width, height = page["page_size"]
        for block in page.get("para_blocks") or []:
            rendered = make_blocks_to_markdown([block], MakeMode.MM_MD, "images")
            if not rendered:
                continue
            paragraph = rendered[0]
            kind = block["type"]
            if kind == "title":
                level = get_title_level(block)
                if level > 0:
                    while headings and headings[-1][0] >= level:
                        headings.pop()
                    headings.append((level, paragraph[level:].strip()))
            # Java chunk coordinates count UTF-16 code units, including the blank line separator.
            start = offset + (2 if paragraphs else 0)
            offset = start + len(paragraph.encode("utf-16-le")) // 2
            paragraphs.append(paragraph)
            bbox = block.get("bbox")
            sources.append({
                "startOffset": start,
                "endOffset": offset,
                "pageIndex": page["page_idx"],
                "headingPath": [title for _, title in headings],
                "type": kind,
                "bbox": [int(value * 1000 / extent)
                         for value, extent in zip(bbox, (width, height, width, height))] if bbox else [],
                "imagePaths": list(dict.fromkeys(image_paths(block))),
            })
    return "\n\n".join(paragraphs), sources


def convert_office(root, suffix, data):
    source = root / ("document" + suffix)
    source.write_bytes(data)
    pdf = root / "document.pdf"
    try:
        result = subprocess.run([
            "soffice", "-env:UserInstallation=" + (root / "office-profile").as_uri(),
            "--headless", "--convert-to", "pdf", "--outdir", str(root), str(source),
        ], capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(422, "Office-to-PDF conversion timed out") from exc
    if result.returncode or not pdf.is_file():
        raise HTTPException(422, f"Office-to-PDF conversion failed: {result.stderr.strip()}")
    return pdf.read_bytes()


@app.post("/file_parse")
async def file_parse(files: list[UploadFile] = File(...)):
    if len(files) != 1:
        raise HTTPException(400, "Upload one document per request")
    upload = files[0]
    suffix = Path(upload.filename or "").suffix.lower()
    if suffix not in {".pdf", ".pptx", ".docx"}:
        raise HTTPException(400, "Expected PDF, PPTX or DOCX")
    async with gate:
        data = await upload.read(limit + 1)
        if len(data) > limit:
            raise HTTPException(413, "Maximum document size is 100 MiB")
        work = tempfile.TemporaryDirectory(prefix="studypilot-mineru-")
        root = Path(work.name)
        try:
            if suffix != ".pdf":
                data = await asyncio.to_thread(convert_office, root, suffix, data)
            await aio_do_parse(
                output_dir=str(root), pdf_file_names=["document"], pdf_bytes_list=[data],
                p_lang_list=["ch"], backend="pipeline", parse_method="auto",
                formula_enable=True, table_enable=True, server_url=None,
                f_draw_layout_bbox=False, f_draw_span_bbox=False, f_dump_md=False,
                f_dump_middle_json=True, f_dump_model_output=False, f_dump_orig_pdf=False,
                f_dump_content_list=False, start_page_id=0, end_page_id=99999,
            )
            result = root / "document" / "auto"
            middle = json.loads((result / "document_middle.json").read_text(encoding="utf-8"))
            markdown, sources = render(middle["pdf_info"])
            if not markdown.strip():
                raise HTTPException(422, "MinerU returned an empty document")
            archive = root / "document.zip"
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
                output.writestr("document.md", markdown)
                output.writestr("document.sources.json", json.dumps(sources, ensure_ascii=False))
                for image in sorted({image for block in sources for image in block["imagePaths"]}):
                    output.write(result / image, image)
            return FileResponse(
                archive, media_type="application/zip", filename=archive.name,
                headers={"X-Parser-Version": parser_version}, background=BackgroundTask(work.cleanup),
            )
        except BaseException:
            work.cleanup()
            raise
