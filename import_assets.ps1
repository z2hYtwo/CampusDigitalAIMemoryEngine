# CDAME Batch Import Script
# Upload PDF and DOCX files from root to cdame-ai-service

$baseUrl = "http://localhost:8080/api/asset/upload"

# Get files more reliably
$files = Get-ChildItem -Path "." -File | Where-Object { $_.Extension -match "^\.(pdf|docx|txt|doc)$" }

Write-Host "Starting batch import of campus history assets..." -ForegroundColor Cyan
Write-Host "Found $($files.Count) files to upload.`n"

foreach ($file in $files) {
    Write-Host "Uploading: $($file.Name)..." -NoNewline
    
    # Use quotes for the file path to handle spaces
    $filePath = $file.FullName
    $response = curl.exe -s -X POST $baseUrl `
        -F "file=@""$filePath"""
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host " [SUCCESS]" -ForegroundColor Green
    } else {
        Write-Host " [FAILED]" -ForegroundColor Red
        Write-Host "Error Details: $response"
    }
}

Write-Host "`nBatch import completed!" -ForegroundColor Cyan
