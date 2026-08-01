cmake_minimum_required(VERSION 3.16)
project(native-renderer
    VERSION 1.0.0
    LANGUAGES CXX
    DESCRIPTION "Native rendering engine for TransferStation WhimsicalIdeas"
)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Java home for JNI headers
if(NOT DEFINED JAVA_HOME)
    if(DEFINED ENV{JAVA_HOME})
        set(JAVA_HOME "$ENV{JAVA_HOME}")
    elseif(WIN32)
        set(JAVA_HOME "C:/Program Files/Java/jdk-17")
    elseif(APPLE)
        execute_process(COMMAND /usr/libexec/java_home OUTPUT_VARIABLE JAVA_HOME OUTPUT_STRIP_TRAILING_WHITESPACE)
    else()
        execute_process(COMMAND readlink -f /usr/bin/java OUTPUT_VARIABLE JAVA_BIN OUTPUT_STRIP_TRAILING_WHITESPACE)
        get_filename_component(JAVA_HOME "${JAVA_BIN}" DIRECTORY)
        get_filename_component(JAVA_HOME "${JAVA_HOME}" DIRECTORY)
    endif()
endif()
message(STATUS "Java home: ${JAVA_HOME}")

# ── Source file groups ──────────────────────────────────────────────
set(CORE_SOURCES
    src/mdl_parser.cpp
    src/vvd_parser.cpp
    src/vtx_parser.cpp
    src/vtf_decoder.cpp
    src/skinning.cpp
    src/phy_parser.cpp
    src/model_loader.cpp
    src/model_manager.cpp
    src/model_diagnostics.cpp
    src/physics_simulation.cpp
)

set(RENDER_SOURCES
    src/gl_renderer.cpp
    src/glad_loader.cpp
    src/gpu_skinning.cpp
    src/shader_generator.cpp
)

set(JNI_SOURCES
    src/native_bridge.cpp
)

set(CORE_JNI_SOURCES
    src/native_core_bridge.cpp
)

# ── Object libraries (compiled once, consumed by final targets) ─────
add_library(core OBJECT ${CORE_SOURCES})
add_library(render OBJECT ${RENDER_SOURCES})

target_include_directories(core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")

target_include_directories(render PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")

# ── Platform-specific final targets ─────────────────────────────────
if(WIN32)
    # Windows: single native-renderer.dll (core + render + JNI)
    add_definitions(-DWIN32_LEAN_AND_MEAN -DNOMINMAX)
    add_library(${PROJECT_NAME} SHARED
        ${JNI_SOURCES}
        ${CORE_JNI_SOURCES}
    )
    target_link_libraries(${PROJECT_NAME} PRIVATE core render)
    target_include_directories(${PROJECT_NAME} PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
    )
    if(MSVC)
        target_compile_options(${PROJECT_NAME} PRIVATE /O2 /MT /EHsc)
    else()
        target_compile_options(${PROJECT_NAME} PRIVATE -O2 -static)
    endif()
    target_link_libraries(${PROJECT_NAME} PRIVATE
        opengl32
        user32
        gdi32
        gdiplus
        ntdll
    )
    target_include_directories(${PROJECT_NAME} PRIVATE
        "${JAVA_HOME}/include"
        "${JAVA_HOME}/include/win32"
    )
    set_target_properties(${PROJECT_NAME} PROPERTIES
        OUTPUT_NAME "native-renderer"
        SUFFIX ".dll"
    )

elseif(ANDROID)
    # Android: only native-core.so (no GL rendering)
    # Android NDK provides jni.h natively — no JAVA_HOME needed
    target_include_directories(core PUBLIC "${JAVA_HOME}/include" "${JAVA_HOME}/include/linux")

    add_library(native-core SHARED
        $<TARGET_OBJECTS:core>
        ${CORE_JNI_SOURCES}
    )
    target_compile_options(native-core PRIVATE -O2 -fPIC)
    target_link_libraries(native-core
        ${CMAKE_DL_LIBS}
        log
    )
    set_target_properties(native-core PROPERTIES
        OUTPUT_NAME "native-core"
        SUFFIX ".so"
    )

elseif(UNIX)
    # Linux: libnative-core.so + libnative-render.so
    target_include_directories(core PUBLIC "${JAVA_HOME}/include" "${JAVA_HOME}/include/linux")

    add_library(native-core SHARED
        $<TARGET_OBJECTS:core>
        ${CORE_JNI_SOURCES}
    )
    target_compile_options(native-core PRIVATE -O2 -fPIC)
    target_link_libraries(native-core
        ${CMAKE_DL_LIBS}
        pthread
    )
    set_target_properties(native-core PROPERTIES
        OUTPUT_NAME "native-core"
        SUFFIX ".so"
    )

    add_library(native-render SHARED
        $<TARGET_OBJECTS:render>
        ${JNI_SOURCES}
    )
    target_compile_options(native-render PRIVATE -O2 -fPIC)
    target_link_libraries(native-render
        native-core
        ${CMAKE_DL_LIBS}
        GL
        X11
        pthread
    )
    set_target_properties(native-render PROPERTIES
        OUTPUT_NAME "native-render"
        SUFFIX ".so"
    )
endif()

install(TARGETS ${PROJECT_NAME}
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
