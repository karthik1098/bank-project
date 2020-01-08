/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author karthik
 */

    import java.sql.*;  

public class conn{
    Connection c;
    Statement s;
    public conn(){  
        try{  
            Class.forName("com.mysql.jdbc.Driver");  
            c =DriverManager.getConnection("jdbc:mysql:///xxxxx","xxxt","adxxxxn");    
            s =c.createStatement();  
            
        }catch(ClassNotFoundException | SQLException e){ 
            System.out.println(e);
        } 
    }
    public static void main(String[] args)
    {
   new Login().setVisible(true);
    }  
}

  
