//! Native TCP ownership tables; no subprocess on the quit path.
use std::net::{Ipv6Addr, SocketAddr};
use windows_sys::Win32::Foundation::{ERROR_INSUFFICIENT_BUFFER, NO_ERROR};
use windows_sys::Win32::NetworkManagement::IpHelper::{
    GetExtendedTcpTable, MIB_TCP6ROW_OWNER_PID, MIB_TCPROW_OWNER_PID,
    TCP_TABLE_OWNER_PID_CONNECTIONS,
};
use windows_sys::Win32::Networking::WinSock::{AF_INET, AF_INET6};

pub(super) fn server_owned_by(
    server: SocketAddr,
    client: SocketAddr,
    pid: u32,
) -> Result<bool, String> {
    // A Java listener may use an IPv4-mapped IPv6 socket even when bound to 127.0.0.1.
    for family in [AF_INET, AF_INET6] {
        let (table, bytes) = tcp_table(family as u32)?;
        let count = table[0] as usize;
        let row_size = if family == AF_INET {
            std::mem::size_of::<MIB_TCPROW_OWNER_PID>()
        } else {
            std::mem::size_of::<MIB_TCP6ROW_OWNER_PID>()
        };
        if count > (bytes - 4) / row_size {
            return Err("Invalid TCP ownership table".into());
        }
        for i in 0..count {
            // Bounds were checked above; read_unaligned avoids relying on struct alignment.
            let row = unsafe { table.as_ptr().cast::<u8>().add(4 + i * row_size) };
            let (state, local, remote, owner) = if family == AF_INET {
                let row = unsafe { row.cast::<MIB_TCPROW_OWNER_PID>().read_unaligned() };
                let endpoint = |address: u32, port: u32| {
                    Some(SocketAddr::from((
                        address.to_ne_bytes(),
                        u16::from_be(port as u16),
                    )))
                };
                (
                    row.dwState,
                    endpoint(row.dwLocalAddr, row.dwLocalPort),
                    endpoint(row.dwRemoteAddr, row.dwRemotePort),
                    row.dwOwningPid,
                )
            } else {
                let row = unsafe { row.cast::<MIB_TCP6ROW_OWNER_PID>().read_unaligned() };
                let endpoint = |address: [u8; 16], port: u32| {
                    Ipv6Addr::from(address)
                        .to_ipv4_mapped()
                        .map(|address| SocketAddr::from((address, u16::from_be(port as u16))))
                };
                (
                    row.dwState,
                    endpoint(row.ucLocalAddr, row.dwLocalPort),
                    endpoint(row.ucRemoteAddr, row.dwRemotePort),
                    row.dwOwningPid,
                )
            };
            if state == 5 && local == Some(server) && remote == Some(client) {
                return if owner == pid {
                    Ok(true)
                } else {
                    Err("server socket belongs to another process".into())
                };
            }
        }
    }
    Ok(false)
}

fn tcp_table(family: u32) -> Result<(Vec<u32>, usize), String> {
    let mut size = 0;
    let status = unsafe {
        GetExtendedTcpTable(
            std::ptr::null_mut(),
            &mut size,
            0,
            family,
            TCP_TABLE_OWNER_PID_CONNECTIONS,
            0,
        )
    };
    if status != ERROR_INSUFFICIENT_BUFFER {
        return Err(format!("TCP ownership table unavailable ({status})"));
    }
    // The table consists of DWORDs. Allocate aligned storage and handle concurrent table growth.
    for _ in 0..3 {
        if size < 4 {
            return Err("Invalid TCP ownership table size".into());
        }
        let mut table = vec![0u32; (size as usize).div_ceil(4)];
        let capacity = table.len() * 4;
        let status = unsafe {
            GetExtendedTcpTable(
                table.as_mut_ptr().cast(),
                &mut size,
                0,
                family,
                TCP_TABLE_OWNER_PID_CONNECTIONS,
                0,
            )
        };
        if status == ERROR_INSUFFICIENT_BUFFER {
            continue;
        }
        if status != NO_ERROR {
            return Err(format!("TCP ownership table unavailable ({status})"));
        }
        if size < 4 || size as usize > capacity {
            return Err("Invalid TCP ownership table size".into());
        }
        return Ok((table, size as usize));
    }
    Err("TCP ownership table kept changing".into())
}
