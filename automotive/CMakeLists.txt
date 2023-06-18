cmake_minimum_required(VERSION 3.22.0)
project(usb-reset-lib)
add_library(usb-reset-lib SHARED src/main/cpp/usb.c)
target_link_libraries(usb-reset-lib)
