terraform {
  required_version = ">= 1.5"
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.48"
    }
  }
}

provider "hcloud" {
  token = var.hcloud_token
}

# SSH key authorized on the box. Hetzner injects it into root's authorized_keys
# at first boot; cloud-init then copies it to the deploy user and disables root.
resource "hcloud_ssh_key" "admin" {
  name       = "${var.server_name}-admin"
  public_key = file(pathexpand(var.ssh_public_key_path))
}

# Network-level firewall (defense-in-depth with the host UFW set by cloud-init).
# Only SSH (restricted), HTTP, HTTPS, and ICMP are allowed inbound. The app,
# Postgres and Redis ports are never exposed — they live on the Docker network.
resource "hcloud_firewall" "agm" {
  name = "${var.server_name}-fw"

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = [var.admin_ssh_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "80"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "443"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
}

# Durable, snapshot-able data disk. cloud-init formats it (if blank) and mounts
# it at /var/lib/docker, so every container's data survives a server rebuild:
# detach this volume, recreate the server, reattach — data intact.
resource "hcloud_volume" "data" {
  name     = "${var.server_name}-data"
  size     = var.data_volume_size
  location = var.location
  format   = "ext4"
}

resource "hcloud_server" "agm" {
  name        = var.server_name
  server_type = var.server_type
  image       = var.image
  location    = var.location
  ssh_keys    = [hcloud_ssh_key.admin.id]
  firewall_ids = [hcloud_firewall.agm.id]

  user_data = templatefile("${path.module}/../cloud-init.yaml", {
    deploy_user   = var.deploy_user
    ssh_pubkey    = trimspace(file(pathexpand(var.ssh_public_key_path)))
    volume_device = "/dev/disk/by-id/scsi-0HC_Volume_${hcloud_volume.data.id}"
  })

  labels = {
    app = "agent-manager"
  }
}

resource "hcloud_volume_attachment" "data" {
  volume_id = hcloud_volume.data.id
  server_id = hcloud_server.agm.id
  automount = false # cloud-init owns the mount (it must land before Docker starts)
}
