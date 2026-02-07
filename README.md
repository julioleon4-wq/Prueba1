# Selector de asientos para teatro

Este proyecto es un selector de asientos en línea hecho con HTML, CSS y JavaScript.

## Ejecutar localmente

1. Asegúrate de tener Python 3.10+ instalado.
2. Ejecuta el lanzador:

```bash
python app.py
```

Esto abrirá el navegador con el selector y levantará un servidor local.

## Generar un EXE (Windows)

Si necesitas un ejecutable, usa PyInstaller:

```bash
pip install pyinstaller
pyinstaller --onefile --windowed app.py
```

El ejecutable quedará en `dist/app.exe`.

> Nota: el EXE necesita estar en la misma carpeta que `index.html`, `styles.css` y `script.js`,
> o bien copiar esos archivos junto al ejecutable.
