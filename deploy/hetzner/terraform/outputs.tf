output "server_ipv4" {
  description = "Public IPv4. Create a DNS A record (your DOMAIN → this IP) before first deploy so Caddy can issue the TLS cert."
  value       = hcloud_server.agm.ipv4_address
}

output "server_ipv6" {
  description = "Public IPv6. Optionally add a AAAA record."
  value       = hcloud_server.agm.ipv6_address
}

output "data_volume_id" {
  description = "Attached data volume id (holds /var/lib/docker). Snapshot this for DR."
  value       = hcloud_volume.data.id
}

output "next_steps" {
  value = <<-EOT

    Server provisioned: ${hcloud_server.agm.ipv4_address}

    1. DNS: create an A record  <your-domain> -> ${hcloud_server.agm.ipv4_address}
       (and AAAA -> ${hcloud_server.agm.ipv6_address}) and wait for it to resolve.
    2. SSH in as the deploy user:  ssh ${var.deploy_user}@${hcloud_server.agm.ipv4_address}
       (cloud-init takes ~2-3 min on first boot; root login is disabled once done.)
    3. Put the source on the box and configure secrets, then deploy — see deploy/hetzner/README.md.
  EOT
}
