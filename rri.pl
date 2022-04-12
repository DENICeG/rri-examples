# Read answer from RRI-Server 
# 
# Syntax: rriReadData(object socket) 
# Parameter: 
#    - socket: SSL connection established with rriConnect 
# 
# Returnvalue: 
#    Returns string with answer from RRI server on success or "undef", if an error 
#    occured. 
sub rriReadData 
{
my $sock=shift; 
    my ($head, $head2); 
    my ($data, $data2); 
    my $ret; 
    $head=""; 
    $data=""; 
    # Step 1: read 4-byte RRI-header 
    my $rest=4; 
    while ( $rest ) 
{
        $ret=read $sock,$head2,$rest; 
        if (! defined($ret)) 
{
            return (undef); 
}
        $head.=$head2; 
        $rest-=$ret; 
}
    my $len=unpack "N",$head; 
    if ($len > 65535) 
{
        # Should not happen, something went wrong 
        return (undef); 
}
    # Step 2: read payload 
    $rest=$len; 
    while ( $rest ) 
{
        $ret=read $sock,$data2,$rest; 
        if (! defined($ret)) 
{
            return (undef); 
}
        $data.=$data2; 
        $rest-=$ret; 
}
    return $data; 
}

# Sends order to RRI-Server 
# 
# 
# Syntax: rriSendData(object socket, string order) 
# Parameter: 
#    - socket: SSL connection established with rriConnect 
#    - order: String, which should be send to the RRI-server 
# 
# Returnvalue: 
#    Returns 1 on success, 0 on failure 
sub rriSendData 
{
        my ($sock,$data)=@_; 
        my $len=length($data);                # Length of data 
        my $head=pack "N",$len;               # convert to 4 byte value in network byteorder 
        return 0 if (!print $sock $head);     # send 4 byte header 
        return 0 if (!print $sock $data);     # send payload 
        return 1; 
}