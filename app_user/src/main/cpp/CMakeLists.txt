cmake_minimum_required(VERSION 3.22)
project(user_core C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(NATIVE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../native)

# ---- mbedTLS (statik, sabit surum: bkz. THIRD_PARTY.txt) ----
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(GEN_FILES OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
add_subdirectory(${NATIVE_DIR}/third_party/mbedtls ${CMAKE_BINARY_DIR}/mbedtls EXCLUDE_FROM_ALL)

# ---- IXWebSocket (kendi CMake'i yerine dogrudan kaynaklar) ----
file(GLOB IXWS_SRC ${NATIVE_DIR}/third_party/IXWebSocket/ixwebsocket/*.cpp)
add_library(ixwebsocket STATIC ${IXWS_SRC})
target_include_directories(ixwebsocket PUBLIC ${NATIVE_DIR}/third_party/IXWebSocket)
target_compile_definitions(ixwebsocket PUBLIC IXWEBSOCKET_USE_TLS IXWEBSOCKET_USE_MBED_TLS)
target_link_libraries(ixwebsocket PUBLIC mbedtls mbedx509 mbedcrypto)

# ---- client_lib + JNI = libuser_core.so ----
add_library(user_core SHARED
    ${NATIVE_DIR}/client_lib/client_session.cpp
    jni_user.cpp)
target_include_directories(user_core PRIVATE ${NATIVE_DIR}/client_lib)
target_link_libraries(user_core ixwebsocket log)
