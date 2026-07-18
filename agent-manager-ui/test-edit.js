const fs = require('fs');
async function run() {
    const res = await fetch("http://localhost:8080/api/admin/agents", {
        headers: { "Authorization": "Bearer TEST", "Content-Type": "application/json" }
    });
    console.log(await res.text());
}
run();
