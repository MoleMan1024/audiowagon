# SPDX-FileCopyrightText: 2022 MoleMan1024 <moleman1024dev@gmail.com>
# SPDX-License-Identifier: GPL-3.0-or-later

import logging
import signal
import http.server
import socketserver

_FORMAT = "%(asctime)s.%(msecs)03d [%(levelname)-8s] %(message)s"
_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"
_PORT = 8080
_DIRECTORY = r"c:\dev\app\android\sdcard_images\Music"


class Handler(http.server.SimpleHTTPRequestHandler):

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=_DIRECTORY, **kwargs)

    # noinspection PyShadowingBuiltins
    def log_message(self, format, *args):
        logging.debug(f"{self.address_string()}: {format % args}")


# noinspection PyUnusedLocal
def shutdownHandler(self, signum, frame): # pylint: disable=unused-argument
    self._log.info("Ctrl+C ...")
    httpd.shutdown()


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG, format=_FORMAT, datefmt=_DATE_FORMAT)
    signal.signal(signal.SIGINT, shutdownHandler)
    with socketserver.TCPServer(("", _PORT), Handler) as httpd:
        logging.info(f"Starting HTTP server at: {_PORT}")
        httpd.serve_forever()

