@echo off
chcp 65001 >nul
cls
echo ========================================
echo   TVBoxOS Auto Build Script
echo ========================================

:: 如需指定 JDK，请取消注释并设置路径
:: set JAVA_HOME=M:\jdk11
:: set PATH=%JAVA_HOME%\bin;%PATH%

echo.
echo [INFO] JDK detected
echo [INFO] 准备构建 APK
echo.

:: 如果命令行传递了任务参数（例如 build.bat assembleGenericJava32Release），则直接构建
if not "%1"=="" (
    set TASK=%1
    goto :buildTask
)

:menu
set "choice="
echo 可用的构建任务：
echo   -------- 通用版 (generic) --------
echo   1. assembleGenericJava32Release
echo   2. assembleGenericJava64Release
echo   3. assembleGenericPythonRelease
echo   4. assembleGenericPython32Release
echo   5. assembleGenericPython64Release
echo   -------- 海信版 (hisense) --------
echo   6. assembleHisenseJava32Release
echo   7. assembleHisenseJava64Release
echo   8. assembleHisensePythonRelease
echo   9. assembleHisensePython32Release
echo  10. assembleHisensePython64Release
echo   -------- 其他 --------
echo  11. assembleGenericRelease     (通用全部 mode)
echo  12. assembleHisenseRelease     (海信全部 mode)
echo  13. 全部构建(All)
echo   0. 退出
echo.
set /p choice="请输入选项 (0-13)，直接回车默认1: "
if not defined choice (
    echo 未输入选项，默认执行 1.assembleGenericJava32Release
    set choice=1
)
if "%choice%"=="1"  set TASK=assembleGenericJava32Release & goto :buildTask
if "%choice%"=="2"  set TASK=assembleGenericJava64Release & goto :buildTask
if "%choice%"=="3"  set TASK=assembleGenericPythonRelease & goto :buildTask
if "%choice%"=="4"  set TASK=assembleGenericPython32Release & goto :buildTask
if "%choice%"=="5"  set TASK=assembleGenericPython64Release & goto :buildTask
if "%choice%"=="6"  set TASK=assembleHisenseJava32Release & goto :buildTask
if "%choice%"=="7"  set TASK=assembleHisenseJava64Release & goto :buildTask
if "%choice%"=="8"  set TASK=assembleHisensePythonRelease & goto :buildTask
if "%choice%"=="9"  set TASK=assembleHisensePython32Release & goto :buildTask
if "%choice%"=="10" set TASK=assembleHisensePython64Release & goto :buildTask
if "%choice%"=="11" set TASK=assembleGenericRelease & goto :buildTask
if "%choice%"=="12" set TASK=assembleHisenseRelease & goto :buildTask
if "%choice%"=="13" goto :buildAll
if "%choice%"=="0" exit /b

echo 无效选项，请重新选择。
goto :menu

:buildTask
echo.
echo [INFO] 正在构建 %TASK% ...
call gradlew.bat app:%TASK%
if errorlevel 1 (
    echo.
    echo [ERROR] 构建 %TASK% 失败！
    pause
    exit /b 1
) else (
    echo.
    echo ========================================
    echo [SUCCESS] %TASK% 构建成功！
    echo APK 路径：app\build\outputs\apk\release\
    echo ========================================
)
pause
exit /b

:buildAll
echo.
echo [INFO] 开始构建所有任务...
set TASKS=assembleGenericJava32Release assembleGenericJava64Release assembleGenericPythonRelease assembleGenericPython32Release assembleGenericPython64Release assembleHisenseJava32Release assembleHisenseJava64Release assembleHisensePythonRelease assembleHisensePython32Release assembleHisensePython64Release
for %%t in (%TASKS%) do (
    echo.
    echo ========================================
    echo [BUILD] %%t
    echo ========================================
    call gradlew.bat app:%%t
    if errorlevel 1 (
        echo [ERROR] 构建 %%t 失败！
        pause
        exit /b 1
    )
)
echo.
echo ========================================
echo [SUCCESS] 所有任务构建完成！
echo ========================================
pause
exit /b