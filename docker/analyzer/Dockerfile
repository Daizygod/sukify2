# Анализатор аудио: BPM, сетка долей, тональность (камелот) и пики волны.
# Живёт отдельным сервисом, чтобы не тащить Python и Essentia в php-образ.
# Колесо Essentia самодостаточно — системные библиотеки ставить не нужно.
FROM python:3.10-slim

COPY docker/analyzer/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY docker/analyzer/app.py /app/app.py
WORKDIR /app

EXPOSE 8300
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8300", "--workers", "1"]
