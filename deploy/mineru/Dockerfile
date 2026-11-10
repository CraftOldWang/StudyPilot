ARG MINERU_BASE_IMAGE=know-engine-mineru
FROM ${MINERU_BASE_IMAGE}
RUN apt-get update && apt-get install -y --no-install-recommends libreoffice-impress && rm -rf /var/lib/apt/lists/*
WORKDIR /service
COPY server.py .
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
