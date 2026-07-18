variable "hcloud_token" {
  description = "Hetzner Cloud API token (Project → Security → API tokens, Read & Write)."
  type        = string
  sensitive   = true
}

variable "server_name" {
  description = "Name of the Hetzner Cloud server."
  type        = string
  default     = "agent-manager"
}

variable "server_type" {
  description = "Hetzner server type. CPX41 = 8 vCPU / 16 GB / 240 GB — the recommended all-in-one size (JVM + Postgres + Redis + Caddy)."
  type        = string
  default     = "cpx41"
}

variable "location" {
  description = "Hetzner location. EU: nbg1 (Nuremberg), fsn1 (Falkenstein), hel1 (Helsinki). US: ash (Ashburn), hil (Hillsboro)."
  type        = string
  default     = "nbg1"
}

variable "image" {
  description = "Base OS image."
  type        = string
  default     = "ubuntu-24.04"
}

variable "ssh_public_key_path" {
  description = "Path to the SSH public key that will be authorized for the deploy user (and root, until cloud-init disables root login)."
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "admin_ssh_cidr" {
  description = "CIDR allowed to reach SSH (port 22) through the Hetzner Cloud Firewall. Set to your office/home IP (e.g. 203.0.113.4/32). Default 0.0.0.0/0 is open — tighten it."
  type        = string
  default     = "0.0.0.0/0"
}

variable "data_volume_size" {
  description = "Size (GB) of the attached Hetzner Volume that holds /var/lib/docker (all container data: Postgres, Redis, Caddy certs). Snapshot-able and resizable independently of the server."
  type        = number
  default     = 50
}

variable "deploy_user" {
  description = "Unprivileged Linux user that owns the checkout and runs docker compose."
  type        = string
  default     = "deploy"
}
