@echo off
chcp 936 >nul
cls
echo ==============================================
echo      合体版：先预览，确认后再删除apk
echo 源目录：%~dp0TVBoxOS\app\build\outputs\apk\
echo ==============================================
set "SRC_ROOT=%~dp0\app\build\outputs\apk\"

if not exist "%SRC_ROOT%" (
    echo [警告] 源目录不存在！
    exit /b
)

echo.
echo ----------【预览：下面是将要被删除的全部apk】----------
set "count=0"
for /r "%SRC_ROOT%" %%a in (*.apk) do (
    echo %%a
    set /a count+=1
)
echo --------------------------------------------------------
echo 总计待删除文件数量：%count% 个
echo.

if %count% equ 0 (
    echo 没有找到任何apk文件，直接退出。
    exit /b
)

echo ?? 【第一次确认】上面列表确认无误，请按回车开始真实删除！
echo    如果不想删除，直接关闭本窗口退出。


echo.
echo ################ 开始执行删除操作 ################
set "delCnt=0"
for /r "%SRC_ROOT%" %%a in (*.apk) do (
    echo 删除：%%a
    del /f /q "%%a"
    set /a delCnt+=1
)
echo.
echo ? 删除完成，共处理 %delCnt% 个apk；文件夹结构全部保留。
echo ==============================================
echo 【第二次回车：结束脚本】
pause

