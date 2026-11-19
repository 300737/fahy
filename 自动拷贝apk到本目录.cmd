@echo off
chcp 936 >nul
cls
echo ==============================================
echo 递归扫描 TVBoxOS\app\build\outputs\apk 全部apk
echo 同名文件自动加目录后缀防覆盖，输出平铺到脚本目录
echo ==============================================

set "SRC_ROOT=%~dp0\app\build\outputs\apk\"

if not exist "%SRC_ROOT%" (
    echo [错误]源目录不存在：%SRC_ROOT%
    exit /b 1
)

echo 源搜索目录：%SRC_ROOT%
echo 输出目录：%~dp0
echo.

for /r "%SRC_ROOT%" %%a in (*.apk) do (
    setlocal enabledelayedexpansion
    set "fullpath=%%~dpa"
    set "filename=%%~nxa"
    :: 获取apk直接父文件夹名称（例如 genericJava32 release）
    for %%b in ("!fullpath!\.") do set "parentDir=%%~nxb"
    echo 复制：!filename!  [!parentDir!]
    copy "%%a" "%~dp0!parentDir!_!filename!" /y
    endlocal
)

echo.
echo ==============================================
echo ? 全部复制完成！同名文件已经追加目录前缀
echo ==============================================

