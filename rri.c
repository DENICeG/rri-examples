//Lesen von Daten:
int RRI_Read(SSL *ssl, char **result)
{
	*result=NULL;
	int s;
	int nl=0;
	int size=0;
	s=SSL_read(ssl,(char*)&nl,4);
	if (s!=4) {
		// Error
		return 0;
	}
	size=ntohl(nl);
	char *buf=(char*)malloc(size+2);
	if (!buf) {
		// Not enough memory
		return 0;
	}
	s=SSL_read(ssl,buf,size);
	if (s!=size) {
		// Error
		free(buf);
		return 0;
	}
	buf[size]=0;
	*result=buf;
	SetError(0);
	return size;}
	
	
//Senden von Daten:
int RRI_Send(SSL *ssl, const char *order)
{
	int size=(int)strlen(order);
	int nl=htonl(size);
	int s;
	int x1,x2;
	// Send 4-Byte perfix with length of order in bytes
	s=SSL_write(ssl,(char*)&nl,4);
	if (s!=4) {
		// Error
		return 0;
	}
	// Send order
	s=SSL_write(ssl,order,size);
	if (s!=size) {
		// Error
		return 0;
	}
	return s;
}