@echo off
rem Lance l'interface VDL sans console
start "" "%~dp0.venv\Scripts\pythonw.exe" -m vdl.gui.app
