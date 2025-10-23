package com.tazifor.rtb.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class RtbController {
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>RTB Engine - Geo Visualization</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    .container {
                        background: white;
                        padding: 40px;
                        border-radius: 12px;
                        box-shadow: 0 10px 40px rgba(0,0,0,0.2);
                        max-width: 600px;
                        width: 100%;
                    }
                    h1 {
                        margin-bottom: 10px;
                        color: #333;
                    }
                    p {
                        color: #666;
                        margin-bottom: 30px;
                    }
                    .options {
                        display: grid;
                        gap: 15px;
                    }
                    .option {
                        display: flex;
                        align-items: center;
                        padding: 20px;
                        border: 2px solid #e0e0e0;
                        border-radius: 8px;
                        text-decoration: none;
                        color: #333;
                        transition: all 0.3s;
                    }
                    .option:hover {
                        border-color: #667eea;
                        background: #f8f9ff;
                        transform: translateY(-2px);
                    }
                    .option-icon {
                        font-size: 32px;
                        margin-right: 20px;
                    }
                    .option-content h3 {
                        margin-bottom: 5px;
                        color: #333;
                    }
                    .option-content p {
                        margin: 0;
                        font-size: 14px;
                        color: #888;
                    }
                    .badge {
                        background: #0a0;
                        color: white;
                        padding: 4px 8px;
                        border-radius: 4px;
                        font-size: 11px;
                        margin-left: 10px;
                    }
                    .api-section {
                        margin-top: 30px;
                        padding-top: 20px;
                        border-top: 1px solid #e0e0e0;
                    }
                    .api-section h3 {
                        margin-bottom: 10px;
                        font-size: 14px;
                        color: #666;
                    }
                    .api-section ul {
                        list-style: none;
                        font-size: 13px;
                        color: #888;
                    }
                    .api-section li {
                        margin: 5px 0;
                    }
                    code {
                        background: #f5f5f5;
                        padding: 2px 6px;
                        border-radius: 3px;
                        font-family: 'Monaco', 'Courier New', monospace;
                        font-size: 12px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>RTB Engine Geo Visualization</h1>
                    <p>Choose how you want to visualize campaign geo-targeting</p>

                    <div class="options">
                        <a href="/map.html" class="option">
                            <div class="option-icon">🗺️</div>
                            <div class="option-content">
                                <h3>Interactive Map <span class="badge">RECOMMENDED</span></h3>
                                <p>Leaflet.js-based interactive map with zoom, pan, and layer controls</p>
                            </div>
                        </a>

                        <a href="/benchmark.html" class="option">
                            <div class="option-icon">⚡</div>
                            <div class="option-content">
                                <h3>Performance Benchmark</h3>
                                <p>Compare point-in-polygon vs tile-based precomputation performance</p>
                            </div>
                        </a>

                        <a href="/api/geo/viz/nike" class="option">
                            <div class="option-icon">📊</div>
                            <div class="option-content">
                                <h3>SVG Visualization</h3>
                                <p>Static SVG with optimized pattern-based grid rendering</p>
                            </div>
                        </a>
                    </div>

                    <div class="api-section">
                        <h3>API Endpoints:</h3>
                        <ul>
                            <li>
                                <code>POST /api/geo/campaign/{id}/load-demo</code> - Load demo campaign
                            </li>
                            <li>
                                <code>GET /api/geo/geojson/{id}</code> - Get GeoJSON data
                            </li>
                            <li>
                                <code>GET /api/geo/viz/{id}</code> - Get SVG visualization
                            </li>
                            <li>
                                <code>GET /api/geo/membership/{id}?lat=X&lon=Y</code> - Check point membership
                            </li>
                        </ul>
                    </div>
                </div>
            </body>
            </html>
            """;
    }
}
