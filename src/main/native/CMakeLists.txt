cmake_minimum_required(VERSION 3.16)
project(native-renderer
    VERSION 1.0.0
    LANGUAGES CXX
    DESCRIPTION "Native rendering engine for TransferStation WhimsicalIdeas"
)

# Standard C++20
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

# Sources
file(GLOB NATIVE_SOURCES "src/*.cpp")

# Include directories
set(NATIVE_INCLUDES
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${JAVA_HOME}/include"
)

# Platform-specific configuration
if(WIN32)
    set(NATIVE_INCLUDES ${NATIVE_INCLUDES} "${JAVA_HOME}/include/win32")
    add_definitions(-DWIN32_LEAN_AND_MEAN -DNOMINMAX)
    add_library(${PROJECT_NAME} SHARED ${NATIVE_SOURCES})
    target_compile_options(${PROJECT_NAME} PRIVATE /O2 /MT /EHsc)
    target_link_libraries(${PROJECT_NAME}
        opengl32
        user32
        gdi32
        gdiplus
        ntdll
    )
elseif(APPLE)
    set(NATIVE_INCLUDES ${NATIVE_INCLUDES} "${JAVA_HOME}/include/darwin")
    add_library(${PROJECT_NAME} SHARED ${NATIVE_SOURCES})
    target_compile_options(${PROJECT_NAME} PRIVATE -O2 -fPIC)
    target_link_libraries(${PROJECT_NAME}
        ${CMAKE_DL_LIBS}
        "-framework OpenGL"
        "-framework Cocoa"
    )
elseif(UNIX)
    set(NATIVE_INCLUDES ${NATIVE_INCLUDES} "${JAVA_HOME}/include/linux")
    add_library(${PROJECT_NAME} SHARED ${NATIVE_SOURCES})
    target_compile_options(${PROJECT_NAME} PRIVATE -O2 -fPIC)
    target_link_libraries(${PROJECT_NAME}
        ${CMAKE_DL_LIBS}
        GL
        X11
        pthread
    )
endif()

target_include_directories(${PROJECT_NAME} PRIVATE ${NATIVE_INCLUDES})

# Output naming
if(WIN32)
    set_target_properties(${PROJECT_NAME} PROPERTIES
        OUTPUT_NAME "native-renderer"
        SUFFIX ".dll"
    )
elseif(APPLE)
    set_target_properties(${PROJECT_NAME} PROPERTIES
        OUTPUT_NAME "native-renderer"
        SUFFIX ".dylib"
    )
else()
    set_target_properties(${PROJECT_NAME} PROPERTIES
        OUTPUT_NAME "native-renderer"
        SUFFIX ".so"
    )
endif()

# Post-build: copy DLL to resources directory
set(NATIVE_RESOURCES_DIR "${CMAKE_SOURCE_DIR}/../../resources/natives/${CMAKE_SYSTEM_NAME}")
if(WIN32)
    string(TOLOWER "${CMAKE_SYSTEM_NAME}" SYSTEM_LOWER)
    set(NATIVE_RESOURCES_DIR "${CMAKE_SOURCE_DIR}/../../resources/natives/${SYSTEM_LOWER}")
endif()

add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${NATIVE_RESOURCES_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:${PROJECT_NAME}> "${NATIVE_RESOURCES_DIR}/"
    COMMENT "Copying native library to resources/natives/"
)

# Installation
install(TARGETS ${PROJECT_NAME}
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)