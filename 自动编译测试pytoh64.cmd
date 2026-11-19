@echo on
chcp 65001 >nul
echo 自动编译
::call gradlew.bat clean
call gradlew.bat app:assembleGenericPython64Debug
