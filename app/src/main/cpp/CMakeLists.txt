cmake_minimum_required(VERSION 3.18.1)
project(portalpad_mouse)

add_library(portalpad_mouse SHARED portalpad_mouse.cpp)

find_library(log-lib log)
target_link_libraries(portalpad_mouse ${log-lib})

# Force 16 KB max page size so the .so loads on Android 15+ 16 KB-page devices.
# AGP/NDK default to this on recent toolchains, but pin it explicitly so the
# alignment can't silently regress and crash on newer hardware.
target_link_options(portalpad_mouse PRIVATE "-Wl,-z,max-page-size=16384")
