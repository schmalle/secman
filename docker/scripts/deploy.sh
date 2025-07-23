#!/bin/bash
set -e

echo "🚀 Deploying Secman to production..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

# Change to project directory
cd "$(dirname "$0")/../.."

# Check if docker-compose is available
if ! command -v docker-compose &> /dev/null; then
    print_error "docker-compose is not installed. Please install Docker Compose."
    exit 1
fi

# Check if .env file exists
if [ ! -f .env ]; then
    print_error ".env file not found. Please create it from .env.example and configure production values."
    exit 1
fi

# Verify production environment variables
print_status "Verifying production configuration..."

# Source the .env file
set -a
source .env
set +a

# Check required production variables
REQUIRED_VARS=("MYSQL_ROOT_PASSWORD" "MYSQL_PASSWORD" "JWT_SECRET")
MISSING_VARS=()

for var in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        MISSING_VARS+=("$var")
    fi
done

if [ ${#MISSING_VARS[@]} -ne 0 ]; then
    print_error "Missing required environment variables:"
    printf '%s\n' "${MISSING_VARS[@]}"
    exit 1
fi

# Warn about default values
if [ "$MYSQL_PASSWORD" = "CHANGEME" ]; then
    print_error "MYSQL_PASSWORD is still set to default value. Please change it for production."
    exit 1
fi

if [ "$JWT_SECRET" = "pleasechangethissecrectokeyproductionforsecurityreasonsmustbe256bits" ]; then
    print_error "JWT_SECRET is still set to default value. Please change it for production."
    exit 1
fi

# Parse command line arguments
COMMAND=${1:-deploy}
shift || true

case $COMMAND in
    deploy)
        print_status "Deploying to production..."
        docker-compose up --build -d "$@"
        ;;
    stop)
        print_status "Stopping production deployment..."
        docker-compose down "$@"
        ;;
    restart)
        print_status "Restarting production services..."
        docker-compose restart "$@"
        ;;
    logs)
        print_status "Showing production logs..."
        docker-compose logs -f "$@"
        ;;
    status)
        print_status "Showing production status..."
        docker-compose ps
        ;;
    update)
        print_status "Updating production deployment..."
        docker-compose pull
        docker-compose up --build -d
        ;;
    backup)
        print_status "Creating database backup..."
        BACKUP_FILE="secman-backup-$(date +%Y%m%d-%H%M%S).sql"
        docker-compose exec database mysqldump -u secman -p"$MYSQL_PASSWORD" secman > "$BACKUP_FILE"
        print_status "Backup created: $BACKUP_FILE"
        ;;
    *)
        echo "Usage: $0 {deploy|stop|restart|logs|status|update|backup} [additional docker-compose args]"
        echo ""
        echo "Commands:"
        echo "  deploy   - Deploy to production"
        echo "  stop     - Stop production deployment"
        echo "  restart  - Restart production services"
        echo "  logs     - Show production logs"
        echo "  status   - Show production status"
        echo "  update   - Update production deployment"
        echo "  backup   - Create database backup"
        echo ""
        echo "Examples:"
        echo "  $0 deploy                   # Deploy all services"
        echo "  $0 logs backend             # Show only backend logs"
        echo "  $0 backup                   # Create database backup"
        exit 1
        ;;
esac

if [ "$COMMAND" = "deploy" ]; then
    echo ""
    print_info "Production deployment completed!"
    print_info "Frontend: http://localhost:4321"
    print_info "Backend API: http://localhost:8080"
    print_info "Database: localhost:3306"
    echo ""
    print_warning "Make sure to configure your reverse proxy/load balancer appropriately."
    print_warning "Consider setting up SSL/TLS certificates for production use."
fi