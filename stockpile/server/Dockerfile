FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY stockpile_api ./stockpile_api
ENV DATA_DIR=/data
EXPOSE 8080
CMD ["uvicorn", "stockpile_api.main:app", "--host", "0.0.0.0", "--port", "8080"]
