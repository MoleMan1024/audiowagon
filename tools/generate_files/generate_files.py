# SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
# SPDX-License-Identifier: GPL-3.0-or-later

import logging
import argparse
import sys
import shutil
from pathlib import Path

_FORMAT = "%(asctime)s.%(msecs)03d [%(levelname)-8s] %(message)s"
_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"
_TEMPLATE_MP3_FILE = Path("template.mp3")


def generate2kFiles(outDir: Path):
    for artistNum in range(10):
        for albumNum in range(10):
            albumDir = Path(outDir, f"ARTIST_{artistNum}/ALBUM_{albumNum}")
            for trackNum in range(20):
                if not albumDir.exists():
                    albumDir.mkdir(parents=True)
                track = Path(albumDir, f"TRACK_{trackNum}_FOR_ART_{artistNum}_IN_ALB_{albumNum}.mp3")
                shutil.copyfile(_TEMPLATE_MP3_FILE, track)
            logging.debug(f"Created album: {albumDir}")

def generate20kFiles(outDir: Path):
    for artistNum in range(130):
        for albumNum in range(10):
            albumDir = Path(outDir, f"ARTIST_{artistNum}/ALBUM_{albumNum}")
            for trackNum in range(20):
                if not albumDir.exists():
                    albumDir.mkdir(parents=True)
                track = Path(albumDir, f"TRACK_{trackNum}_FOR_ART_{artistNum}_IN_ALB_{albumNum}.mp3")
                shutil.copyfile(_TEMPLATE_MP3_FILE, track)
            logging.debug(f"Created album: {albumDir}")


def genDirWithMoreThan128Files(outDir: Path):
    """
    Used for reproducing libaums issue: https://github.com/magnusja/libaums/issues/298
    """
    tooManyFilesDir = Path(outDir, "tooManyFiles")
    if not tooManyFilesDir.exists():
        tooManyFilesDir.mkdir(parents=True)
    for trackNum in range(140):
        track = Path(tooManyFilesDir, f"TRACK_{trackNum}.mp3")
        shutil.copyfile(_TEMPLATE_MP3_FILE, track)
    logging.debug(f"Created directory with too many files at: {tooManyFilesDir}")


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG, format=_FORMAT, datefmt=_DATE_FORMAT)
    parser = argparse.ArgumentParser(description="Generate files for testing USB filesystem")
    parser.add_argument("outDir", help="Output directory for file generation")
    parser.add_argument("scenario", help="Scenario for generating files: root128, dir128, 2k, mixed")
    parser.add_argument("--tags", help="Modify MP3 metadata for each file (slower but more realistic)")
    args = parser.parse_args()
    outDirPath = Path(args.outDir)
    if not outDirPath.is_dir():
        logging.error(f"Not a directory: {args.outDir}")
        sys.exit(1)
    if args.scenario == "2k":
        generate2kFiles(outDirPath)
    elif args.scenario == "20k":
        generate20kFiles(outDirPath)
    elif args.scenario == "dir128":
        genDirWithMoreThan128Files(outDirPath)
    elif args.scenario == "mixed":
        generate2kFiles(outDirPath)
        genDirWithMoreThan128Files(outDirPath)

    logging.info("DONE")
